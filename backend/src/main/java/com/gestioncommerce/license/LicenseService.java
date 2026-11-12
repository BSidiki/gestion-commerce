package com.gestioncommerce.license;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

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
 * lancement. Une licence est valable jusqu'à sa date d'expiration. Ensuite, le logiciel
 * passe en lecture seule : tout reste consultable et sauvegardable, plus rien ne s'enregistre.
 *
 * Reculer l'horloge du PC ne prolonge rien : le logiciel retient la date la plus tardive
 * qu'il a vue et s'y tient.
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
     * @param expiresOn dernier jour de l'essai ou de la licence
     * @param daysLeft  jours restants, aujourd'hui compris ; 0 ou négatif une fois expiré
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

    public LicenseStatus status() {
        String installId = installId();
        if (publicKey == null) {
            return new LicenseStatus("DESACTIVEE", false, installId, null, null, null, false);
        }
        LocalDate today = today();

        Optional<License> license = currentLicense(installId);
        if (license.isPresent()) {
            License current = license.get();
            long daysLeft = ChronoUnit.DAYS.between(today, current.expiresOn()) + 1;
            boolean valid = daysLeft > 0;
            return new LicenseStatus(valid ? "ACTIVE" : "EXPIREE", !valid, installId, current.shop(),
                    current.expiresOn().toString(), daysLeft, valid && daysLeft <= WARNING_DAYS);
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

    /** Enregistre une licence après l'avoir vérifiée. */
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
        if (license.expiresOn().isBefore(today())) {
            throw new BusinessException("Cette licence a expiré le " + license.expiresOn()
                    + ". Demandez-en une nouvelle.");
        }
        saveSetting(KEY_LICENSE, token.replaceAll("\\s", ""));
        return status();
    }

    // ---------- Utilisé par la restauration ----------

    /** Date la plus tardive vue par cette installation, si elle a déjà été notée. */
    public Optional<LocalDate> lastSeenOn() {
        return setting(KEY_LAST_SEEN_ON).map(LocalDate::parse);
    }

    /**
     * Après une restauration, les réglages sont ceux de l'archive : la licence suit les données.
     * La date la plus tardive vue, elle, ne recule jamais.
     */
    public void keepLastSeenOn(Optional<LocalDate> before) {
        Optional<LocalDate> restored = lastSeenOn();
        if (before.isPresent() && (restored.isEmpty() || restored.get().isBefore(before.get()))) {
            saveSetting(KEY_LAST_SEEN_ON, before.get().toString());
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

    /** La licence enregistrée, si elle est authentique et faite pour cette installation. */
    private Optional<License> currentLicense(String installId) {
        Optional<String> token = setting(KEY_LICENSE);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        try {
            License license = LicenseCodec.verify(token.get(), publicKey);
            return installId.equalsIgnoreCase(license.installId()) ? Optional.of(license) : Optional.empty();
        } catch (InvalidLicenseException e) {
            return Optional.empty();
        }
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
