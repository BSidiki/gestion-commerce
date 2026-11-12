package com.gestioncommerce.auth;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Empreinte des mots de passe : PBKDF2-HMAC-SHA256 avec un sel propre à chaque compte.
 * Aucune dépendance hors du JDK.
 */
public final class PasswordHasher {

    public static final int ITERATIONS = 210_000;
    public static final int MIN_LENGTH = 6;

    private static final int SALT_LENGTH = 16;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    /** @param hash empreinte en base64 ; @param salt sel en base64 */
    public record Hashed(String hash, String salt, int iterations) {
    }

    public static Hashed hash(String password) {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return new Hashed(Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS)),
                Base64.getEncoder().encodeToString(salt), ITERATIONS);
    }

    public static boolean matches(String password, Hashed stored) {
        if (password == null) {
            return false;
        }
        byte[] expected = Base64.getDecoder().decode(stored.hash());
        byte[] actual = derive(password, Base64.getDecoder().decode(stored.salt()), stored.iterations());
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Empreinte de mot de passe indisponible.", e);
        } finally {
            spec.clearPassword();
        }
    }
}
