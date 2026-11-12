package com.gestioncommerce.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.gestioncommerce.common.BusinessException;
import com.gestioncommerce.license.LicenseCodec.InvalidLicenseException;
import com.gestioncommerce.license.LicenseCodec.License;

/**
 * Licence hors ligne.
 *
 * Sans licence, le logiciel fonctionne en essai pendant 15 jours à partir du premier
 * lancement. Une licence vaut pour une durée (trois mois par défaut) qui court à partir
 * du jour où elle est enregistrée ; enregistrée avant la fin de la précédente, elle la
 * prolonge sans perdre de jours. À l'échéance, le logiciel passe en lecture seule : tout
 * reste consultable et sauvegardable, plus rien ne s'enregistre.
 *
 * Garde-fous :
 *   - une licence ne s'enregistre qu'une fois, et seulement avant sa date limite ;
 *   - reculer l'horloge du PC ne prolonge rien : le logiciel retient la date la plus
 *     tardive qu'il a vue ;
 *   - la date d'échéance enregistrée est scellée : modifiée à la main dans la base, elle
 *     est ignorée. Ce sceau décourage la retouche, il n'est pas inviolable.
 *
 * Si aucune clé publique n'est embarquée (poste de développement), les licences sont désactivées.
 */
@Service
public class LicenseService {

    public static final int TRIAL_DAYS = 15;
    public static final int WARNING_DAYS = 15;

    public static final String KEY_INSTALL_ID = "install.id";
    public static final String KEY_FIRST_RUN_ON = "install.firstRunOn";
    public static final String KEY_LAST_SEEN_ON = "install.lastSeenOn";
    public static final String KEY_LICENSE = "license.token";
    public static final String KEY_EXPIRES_ON = "license.expiresOn";
    public static final String KEY_SEAL = "license.seal";
    public static final String KEY_USED_IDS = "license.usedIds";

    private static final String PUBLIC_KEY_RESOURCE = "licence/cle-publique.txt";

    private final JdbcClient jdbc;
    private final PublicKey publicKey;

    public LicenseService(JdbcClient jdbc, @Value("${app.license.public-key:}") String configuredKey) {
        this.jdbc = jdbc;
        this.publicKey = loadPublicKey(configuredKey);
    }

    /**
     * @param state     DESACTIVEE, ESSAI, ACTIVE, ESSAI_TERMINE ou EXPIREE
     * @param readOnly  vrai si le logiciel n'enregistre plus rien
     * @param installId code d'installation à communiquer à l'éditeur pour obtenir une licence
     * @param shop      boutique inscrite sur la licence
     * @param expiresOn dernier jour de l'essai ou de la licence, inclus
     * @param daysLeft  jours restants, aujourd'hui compris ; 0 ou négatif une fois l'échéance passée
     * @param warning   vrai quand l'échéance approche
     */
    public record LicenseStatus(
            String state,
            boolean readOnly,
            String installId,
            String shop,
            String expiresOn,
            Long daysLeft,
            boolean warning) {
    }

    /** Ce qu'une restauration ne doit pas faire oublier. */
    public record Memory(Optional<LocalDate> lastSeenOn, Set<String> usedLicenseIds) {
    }

    /** Licence enregistrée, avec l'échéance calculée le jour de son enregistrement. */
    private record Registered(License license, LocalDate expiresOn) {
    }

    public LicenseStatus status() {
        String installId = installId();
        if (publicKey == null) {
            return new LicenseStatus("DESACTIVEE", false, installId, null, null, null, false);
        }
        LocalDate today = today();

        Optional<Registered> registered = registered(installId);
        if (registered.isPresent()) {
            LocalDate expiresOn = registered.get().expiresOn();
            long daysLeft = ChronoUnit.DAYS.between(today, expiresOn) + 1;
            boolean valid = daysLeft > 0;
            return new LicenseStatus(valid ? "ACTIVE" : "EXPIREE", !valid, installId,
                    registered.get().license().shop(), expiresOn.toString(), daysLeft,
                    valid && daysLeft <= WARNING_DAYS);
        }

        LocalDate lastTrialDay = firstRunOn(today).plusDays(TRIAL_DAYS - 1);
        long daysLeft = ChronoUnit.DAYS.between(today, lastTrialDay) + 1;
        boolean inTrial = daysLeft > 0;
        return new LicenseStatus(inTrial ? "ESSAI" : "ESSAI_TERMINE", !inTrial, installId, null,
                lastTrialDay.toString(), daysLeft, inTrial);
    }

    public boolean isReadOnly() {
        return status().readOnly();
    }

    /**
     * Enregistre une licence après l'avoir vérifiée. Sa durée court à partir d'aujourd'hui,
     * ou à partir de l'échéance de la licence en cours si celle-ci est encore valide.
     */
    public LicenseStatus activate(String token) {
        if (publicKey == null) {
            throw new BusinessException("Les licences ne sont pas activées dans cette version du logiciel.");
        }
        License license;
        try {
            license = LicenseCodec.verify(token, publicKey);
        } catch (InvalidLicenseException e) {
            throw new BusinessException(e.getMessage());
        }
        String installId = installId();
        if (!installId.equalsIgnoreCase(license.installId())) {
            throw new BusinessException("Cette licence a été faite pour une autre installation ("
                    + license.installId() + "). Le code de cette installation est " + installId + ".");
        }
        Set<String> usedIds = usedIds();
        if (usedIds.contains(license.id())) {
            throw new BusinessException("Cette licence a déjà été enregistrée. Une licence ne sert qu'une fois : "
                    + "demandez-en une nouvelle pour prolonger.");
        }
        LocalDate today = today();
        if (today.isAfter(license.activateBefore())) {
            throw new BusinessException("Cette licence devait être enregistrée au plus tard le "
                    + license.activateBefore() + ". Demandez-en une nouvelle.");
        }

        // Renouvellement anticipé : la nouvelle durée s'ajoute à ce qui reste.
        LocalDate start = registered(installId)
                .map(Registered::expiresOn)
                .filter(current -> !current.isBefore(today))
                .orElse(today);
        LocalDate expiresOn = start.plusMonths(license.months());

        usedIds.add(license.id());
        saveSetting(KEY_USED_IDS, String.join(",", usedIds));
        saveSetting(KEY_LICENSE, token.replaceAll("\\s", ""));
        saveSetting(KEY_EXPIRES_ON, expiresOn.toString());
        saveSetting(KEY_SEAL, seal(license.id(), installId, expiresOn));
        return status();
    }

    // ---------- Utilisé par la restauration ----------

    public Memory remember() {
        return new Memory(lastSeenOn(), usedIds());
    }

    /**
     * Après une restauration, les réglages sont ceux de l'archive : la licence suit les données.
     * Deux choses, elles, ne s'oublient pas : la date la plus tardive vue ne recule jamais, et
     * une licence déjà enregistrée sur ce PC ne peut pas resservir.
     */
    public void keep(Memory before) {
        Optional<LocalDate> restored = lastSeenOn();
        if (before.lastSeenOn().isPresent()
                && (restored.isEmpty() || restored.get().isBefore(before.lastSeenOn().get()))) {
            saveSetting(KEY_LAST_SEEN_ON, before.lastSeenOn().get().toString());
        }
        Set<String> usedIds = usedIds();
        if (usedIds.addAll(before.usedLicenseIds())) {
            saveSetting(KEY_USED_IDS, String.join(",", usedIds));
        }
    }

    // ---------- Interne ----------

    /** Code d'installation, créé au premier besoin : 12 caractères hexadécimaux en trois groupes. */
    private String installId() {
        Optional<String> existing = setting(KEY_INSTALL_ID);
        if (existing.isPresent()) {
            return existing.get();
        }
        String hex = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String installId = hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12);
        saveSetting(KEY_INSTALL_ID, installId);
        return installId;
    }

    private Optional<LocalDate> lastSeenOn() {
        return setting(KEY_LAST_SEEN_ON).map(LocalDate::parse);
    }

    /** Aujourd'hui, ou la date la plus tardive déjà vue si l'horloge du PC a été reculée. */
    private LocalDate today() {
        LocalDate now = LocalDate.now();
        Optional<LocalDate> lastSeen = lastSeenOn();
        if (lastSeen.isPresent() && lastSeen.get().isAfter(now)) {
            return lastSeen.get();
        }
        if (lastSeen.isEmpty() || lastSeen.get().isBefore(now)) {
            saveSetting(KEY_LAST_SEEN_ON, now.toString());
        }
        return now;
    }

    private LocalDate firstRunOn(LocalDate today) {
        Optional<String> existing = setting(KEY_FIRST_RUN_ON);
        if (existing.isPresent()) {
            return LocalDate.parse(existing.get());
        }
        saveSetting(KEY_FIRST_RUN_ON, today.toString());
        return today;
    }

    private Set<String> usedIds() {
        Set<String> ids = new LinkedHashSet<>();
        setting(KEY_USED_IDS).ifPresent(value -> {
            for (String id : value.split(",")) {
                if (!id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        });
        return ids;
    }

    /**
     * La licence enregistrée, si elle est authentique, faite pour cette installation,
     * et si son échéance n'a pas été retouchée.
     */
    private Optional<Registered> registered(String installId) {
        Optional<String> token = setting(KEY_LICENSE);
        Optional<String> expires = setting(KEY_EXPIRES_ON);
        Optional<String> seal = setting(KEY_SEAL);
        if (token.isEmpty() || expires.isEmpty() || seal.isEmpty()) {
            return Optional.empty();
        }
        try {
            License license = LicenseCodec.verify(token.get(), publicKey);
            LocalDate expiresOn = LocalDate.parse(expires.get());
            boolean intact = installId.equalsIgnoreCase(license.installId())
                    && MessageDigest.isEqual(seal.get().getBytes(StandardCharsets.UTF_8),
                            seal(license.id(), installId, expiresOn).getBytes(StandardCharsets.UTF_8));
            return intact ? Optional.of(new Registered(license, expiresOn)) : Optional.empty();
        } catch (InvalidLicenseException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Empreinte liant l'échéance à la licence et à l'installation. */
    private String seal(String licenseId, String installId, LocalDate expiresOn) {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(
                    concat("gestion-commerce-licence".getBytes(StandardCharsets.UTF_8), publicKey.getEncoded()));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            String message = licenseId + "|" + installId.toUpperCase() + "|" + expiresOn;
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Scellement de la licence indisponible.", e);
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }

    private Optional<String> setting(String key) {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = ?")
                .param(key)
                .query(String.class)
                .optional();
    }

    private void saveSetting(String key, String value) {
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .params(key, value)
                .update();
    }

    private static PublicKey loadPublicKey(String configuredKey) {
        String base64 = configuredKey == null ? "" : configuredKey.trim();
        if (base64.isEmpty()) {
            ClassPathResource resource = new ClassPathResource(PUBLIC_KEY_RESOURCE);
            if (!resource.exists()) {
                return null;
            }
            try {
                base64 = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalStateException("Clé publique des licences illisible.", e);
            }
            if (base64.isEmpty()) {
                return null;
            }
        }
        try {
            return LicenseCodec.publicKey(base64);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Clé publique des licences invalide.", e);
        }
    }
}
