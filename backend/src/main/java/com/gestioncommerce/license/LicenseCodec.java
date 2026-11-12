package com.gestioncommerce.license;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Format et vérification d'une licence. Aucune dépendance hors du JDK.
 *
 * Une licence est un texte « contenu.signature », chaque partie en base64 (variante URL) :
 *   - le contenu est une suite de lignes clé=valeur en UTF-8 ;
 *   - la signature Ed25519 porte sur les octets du contenu.
 * Le logiciel ne contient que la clé publique : il peut vérifier une licence, pas en fabriquer.
 * Ce format est aussi écrit par tools/LicenceTool.java : toute évolution doit être faite des deux côtés.
 */
public final class LicenseCodec {

    private static final String VERSION = "1";

    private LicenseCodec() {
    }

    /**
     * @param id        identifiant de la licence, pour le suivi par l'éditeur
     * @param shop      nom de la boutique
     * @param installId code d'installation auquel la licence est liée
     * @param issuedOn  date d'émission
     * @param expiresOn dernier jour de validité, inclus
     */
    public record License(String id, String shop, String installId, LocalDate issuedOn, LocalDate expiresOn) {
    }

    /** Licence illisible, falsifiée ou destinée à une autre installation. Le message est montré au commerçant. */
    public static class InvalidLicenseException extends Exception {

        public InvalidLicenseException(String message) {
            super(message);
        }
    }

    public static PublicKey publicKey(String base64) throws GeneralSecurityException {
        byte[] encoded = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    /** Vérifie la signature puis lit la licence. */
    public static License verify(String token, PublicKey publicKey) throws InvalidLicenseException {
        String compact = token == null ? "" : token.replaceAll("\\s", "");
        int dot = compact.indexOf('.');
        if (dot <= 0 || dot == compact.length() - 1 || compact.indexOf('.', dot + 1) >= 0) {
            throw new InvalidLicenseException("Ce texte n'est pas une licence. Copiez la licence en entier.");
        }
        byte[] content;
        byte[] signature;
        try {
            content = Base64.getUrlDecoder().decode(compact.substring(0, dot));
            signature = Base64.getUrlDecoder().decode(compact.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            throw new InvalidLicenseException("Ce texte n'est pas une licence. Copiez la licence en entier.");
        }
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(content);
            if (!verifier.verify(signature)) {
                throw new InvalidLicenseException("Cette licence n'est pas authentique.");
            }
        } catch (GeneralSecurityException e) {
            throw new InvalidLicenseException("Cette licence n'est pas authentique.");
        }
        return parse(new String(content, StandardCharsets.UTF_8));
    }

    /** Signe une licence. Sert aux tests ; l'éditeur utilise tools/LicenceTool.java. */
    public static String sign(License license, PrivateKey privateKey) throws GeneralSecurityException {
        byte[] content = format(license).getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(content);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(content) + "." + encoder.encodeToString(signer.sign());
    }

    static String format(License license) {
        return "v=" + VERSION + "\n"
                + "id=" + license.id() + "\n"
                + "boutique=" + oneLine(license.shop()) + "\n"
                + "installation=" + license.installId() + "\n"
                + "emise=" + license.issuedOn() + "\n"
                + "expire=" + license.expiresOn() + "\n";
    }

    private static License parse(String content) throws InvalidLicenseException {
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : content.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        if (!VERSION.equals(fields.get("v"))) {
            throw new InvalidLicenseException("Cette licence a été faite pour une autre version du logiciel.");
        }
        try {
            return new License(required(fields, "id"), required(fields, "boutique"),
                    required(fields, "installation"), LocalDate.parse(required(fields, "emise")),
                    LocalDate.parse(required(fields, "expire")));
        } catch (DateTimeParseException e) {
            throw new InvalidLicenseException("Cette licence est incomplète.");
        }
    }

    private static String required(Map<String, String> fields, String key) throws InvalidLicenseException {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw new InvalidLicenseException("Cette licence est incomplète.");
        }
        return value;
    }

    private static String oneLine(String text) {
        return text.replaceAll("[\\r\\n]+", " ").trim();
    }
}
