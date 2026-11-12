package com.gestioncommerce.backup;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement des archives de sauvegarde. Aucune dépendance hors du JDK.
 *
 * Principe : chaque archive est chiffrée par une clé aléatoire qui lui est propre.
 * Cette clé est rangée deux fois dans l'en-tête, protégée une fois par le mot de passe
 * et une fois par la clé de secours : l'un ou l'autre suffit à ouvrir l'archive.
 *
 * Format du fichier (entiers en gros-boutiste) :
 *   "GCBK" | version (1 octet) | itérations PBKDF2 (4)
 *   | sel du mot de passe (16) | nonce (12) | clé de l'archive protégée (48)
 *   | sel de la clé de secours (16) | nonce (12) | clé de l'archive protégée (48)
 *   | nonce de base (8)
 *   | blocs : longueur (4) | bloc chiffré AES-256-GCM
 * Le contenu est découpé en blocs de 1 Mio chiffrés séparément, pour ne jamais charger
 * toute l'archive en mémoire. Chaque bloc authentifie son numéro et s'il est le dernier :
 * un fichier tronqué, réordonné ou modifié est refusé.
 */
public final class BackupCrypto {

    public static final int PBKDF2_ITERATIONS = 600_000;

    private static final byte[] MAGIC = { 'G', 'C', 'B', 'K' };
    private static final byte VERSION = 1;
    private static final int SALT_LENGTH = 16;
    private static final int NONCE_LENGTH = 12;
    private static final int KEY_LENGTH = 32;
    private static final int TAG_BITS = 128;
    private static final int WRAPPED_LENGTH = KEY_LENGTH + TAG_BITS / 8;
    private static final int BASE_NONCE_LENGTH = 8;
    private static final int CHUNK_SIZE = 1024 * 1024;
    private static final int MAX_CHUNK_CIPHERTEXT = CHUNK_SIZE + TAG_BITS / 8;

    /** Alphabet sans caractères ambigus (pas de 0/O, 1/I/L) : la clé de secours se recopie à la main. */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int RECOVERY_GROUPS = 6;
    private static final int RECOVERY_GROUP_SIZE = 5;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCrypto() {
    }

    /**
     * Ce que le logiciel garde pour chiffrer sans redemander le mot de passe : les sels et
     * les clés dérivées, jamais le mot de passe ni la clé de secours eux-mêmes.
     */
    public record Keys(byte[] passwordSalt, byte[] passwordKek, byte[] recoverySalt, byte[] recoveryKek) {
    }

    /** En-tête d'une archive chiffrée. */
    public record Header(
            int iterations,
            byte[] passwordSalt,
            byte[] passwordNonce,
            byte[] passwordWrapped,
            byte[] recoverySalt,
            byte[] recoveryNonce,
            byte[] recoveryWrapped,
            byte[] baseNonce) {
    }

    // ---------- Clés ----------

    public static byte[] newSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return salt;
    }

    /** Clé de secours lisible, par exemple « K7MPQ-2XWRT-… » : 30 caractères, environ 148 bits. */
    public static String newRecoveryKey() {
        StringBuilder key = new StringBuilder();
        for (int group = 0; group < RECOVERY_GROUPS; group++) {
            if (group > 0) {
                key.append('-');
            }
            for (int i = 0; i < RECOVERY_GROUP_SIZE; i++) {
                key.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
            }
        }
        return key.toString();
    }

    /** Tolère les minuscules, les espaces et l'absence de tirets à la saisie. */
    public static String normalizeRecoveryKey(String typed) {
        return typed.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    public static byte[] deriveKek(char[] secret, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(secret, salt, iterations, KEY_LENGTH * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Dérivation de clé indisponible.", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Fabrique les clés à garder à partir d'un mot de passe et d'une clé de secours. */
    public static Keys newKeys(String password, String recoveryKey) {
        byte[] passwordSalt = newSalt();
        byte[] recoverySalt = newSalt();
        return new Keys(
                passwordSalt,
                deriveKek(password.toCharArray(), passwordSalt, PBKDF2_ITERATIONS),
                recoverySalt,
                deriveKek(normalizeRecoveryKey(recoveryKey).toCharArray(), recoverySalt, PBKDF2_ITERATIONS));
    }

    // ---------- Chiffrement ----------

    public static void encrypt(Path plain, Path encrypted, Keys keys) throws IOException {
        byte[] dataKey = new byte[KEY_LENGTH];
        RANDOM.nextBytes(dataKey);
        byte[] passwordNonce = randomBytes(NONCE_LENGTH);
        byte[] recoveryNonce = randomBytes(NONCE_LENGTH);
        byte[] baseNonce = randomBytes(BASE_NONCE_LENGTH);

        try (InputStream in = Files.newInputStream(plain);
                DataOutputStream out = new DataOutputStream(Files.newOutputStream(encrypted))) {
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(PBKDF2_ITERATIONS);
            out.write(keys.passwordSalt());
            out.write(passwordNonce);
            out.write(gcm(Cipher.ENCRYPT_MODE, keys.passwordKek(), passwordNonce, null, dataKey));
            out.write(keys.recoverySalt());
            out.write(recoveryNonce);
            out.write(gcm(Cipher.ENCRYPT_MODE, keys.recoveryKek(), recoveryNonce, null, dataKey));
            out.write(baseNonce);

            // On lit un bloc d'avance pour savoir, au moment de chiffrer, si le bloc courant est le dernier.
            byte[] current = in.readNBytes(CHUNK_SIZE);
            long index = 0;
            while (true) {
                byte[] next = in.readNBytes(CHUNK_SIZE);
                boolean last = next.length == 0;
                byte[] sealed = gcm(Cipher.ENCRYPT_MODE, dataKey, chunkNonce(baseNonce, index),
                        chunkAad(index, last), current);
                out.writeInt(sealed.length);
                out.write(sealed);
                if (last) {
                    break;
                }
                current = next;
                index++;
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("Chiffrement impossible.", e);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    // ---------- Lecture ----------

    public static boolean isEncrypted(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    public static Header readHeader(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return readHeader(in);
        }
    }

    private static Header readHeader(DataInputStream in) throws IOException {
        try {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IOException("Ce fichier n'est pas une sauvegarde chiffrée.");
            }
            if (in.readByte() != VERSION) {
                throw new IOException("Version de sauvegarde chiffrée inconnue.");
            }
            int iterations = in.readInt();
            if (iterations < 1 || iterations > 10_000_000) {
                throw new IOException("En-tête de sauvegarde invalide.");
            }
            return new Header(iterations, exactly(in, SALT_LENGTH), exactly(in, NONCE_LENGTH),
                    exactly(in, WRAPPED_LENGTH), exactly(in, SALT_LENGTH), exactly(in, NONCE_LENGTH),
                    exactly(in, WRAPPED_LENGTH), exactly(in, BASE_NONCE_LENGTH));
        } catch (EOFException e) {
            throw new IOException("Sauvegarde chiffrée tronquée.", e);
        }
    }

    /** Ouvre l'archive avec les clés gardées par le logiciel. Renvoie null si elles ne correspondent pas. */
    public static byte[] unwrapWithKeys(Header header, Keys keys) {
        byte[] dataKey = unwrap(keys.passwordKek(), header.passwordNonce(), header.passwordWrapped());
        return dataKey != null ? dataKey : unwrap(keys.recoveryKek(), header.recoveryNonce(), header.recoveryWrapped());
    }

    /**
     * Ouvre l'archive avec ce que la personne a saisi : essayé comme mot de passe, puis comme
     * clé de secours. Renvoie null si aucun des deux ne convient.
     */
    public static byte[] unwrapWithSecret(Header header, String secret) {
        byte[] kek = deriveKek(secret.toCharArray(), header.passwordSalt(), header.iterations());
        byte[] dataKey = unwrap(kek, header.passwordNonce(), header.passwordWrapped());
        if (dataKey != null) {
            return dataKey;
        }
        String normalized = normalizeRecoveryKey(secret);
        if (normalized.isEmpty()) {
            return null;
        }
        kek = deriveKek(normalized.toCharArray(), header.recoverySalt(), header.iterations());
        return unwrap(kek, header.recoveryNonce(), header.recoveryWrapped());
    }

    public static void decrypt(Path encrypted, Path plain, byte[] dataKey) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(encrypted));
                OutputStream out = Files.newOutputStream(plain)) {
            Header header = readHeader(in);
            long index = 0;
            boolean finished = false;
            while (!finished) {
                int length;
                try {
                    length = in.readInt();
                } catch (EOFException e) {
                    throw new IOException("Sauvegarde chiffrée tronquée.", e);
                }
                if (length < TAG_BITS / 8 || length > MAX_CHUNK_CIPHERTEXT) {
                    throw new IOException("Sauvegarde chiffrée endommagée.");
                }
                byte[] sealed = exactly(in, length);
                byte[] nonce = chunkNonce(header.baseNonce(), index);
                // On ne sait pas d'avance si ce bloc est le dernier : un seul des deux essais peut réussir.
                byte[] opened = open(dataKey, nonce, chunkAad(index, false), sealed);
                if (opened == null) {
                    opened = open(dataKey, nonce, chunkAad(index, true), sealed);
                    if (opened == null) {
                        throw new IOException("Sauvegarde chiffrée endommagée ou clé incorrecte.");
                    }
                    finished = true;
                }
                out.write(opened);
                index++;
            }
            if (in.read() != -1) {
                throw new IOException("Sauvegarde chiffrée endommagée : données en trop.");
            }
        } catch (EOFException e) {
            throw new IOException("Sauvegarde chiffrée tronquée.", e);
        }
    }

    // ---------- Outils ----------

    private static byte[] unwrap(byte[] kek, byte[] nonce, byte[] wrapped) {
        return open(kek, nonce, null, wrapped);
    }

    /** Déchiffre et vérifie ; renvoie null si la clé est mauvaise ou si les données ont été modifiées. */
    private static byte[] open(byte[] key, byte[] nonce, byte[] aad, byte[] sealed) {
        try {
            return gcm(Cipher.DECRYPT_MODE, key, nonce, aad, sealed);
        } catch (AEADBadTagException e) {
            return null;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Déchiffrement indisponible.", e);
        }
    }

    private static byte[] gcm(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] input)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(input);
    }

    /** Nonce d'un bloc : 8 octets aléatoires propres à l'archive, puis le numéro du bloc. */
    private static byte[] chunkNonce(byte[] baseNonce, long index) {
        if (index > Integer.MAX_VALUE) {
            throw new IllegalStateException("Archive trop volumineuse.");
        }
        return ByteBuffer.allocate(NONCE_LENGTH).put(baseNonce).putInt((int) index).array();
    }

    private static byte[] chunkAad(long index, boolean last) {
        return ByteBuffer.allocate(9).putLong(index).put((byte) (last ? 1 : 0)).array();
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static byte[] exactly(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return bytes;
    }
}
