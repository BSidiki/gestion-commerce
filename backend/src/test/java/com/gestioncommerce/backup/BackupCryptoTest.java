package com.gestioncommerce.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.gestioncommerce.backup.BackupCrypto.Header;
import com.gestioncommerce.backup.BackupCrypto.Keys;

class BackupCryptoTest {

    private static final String PASSWORD = "mot de passe du commerçant";
    private static final int CHUNK = 1024 * 1024;

    static String recoveryKey;
    static Keys keys;

    @TempDir
    Path dir;

    @BeforeAll
    static void deriveKeysOnce() {
        recoveryKey = BackupCrypto.newRecoveryKey();
        keys = BackupCrypto.newKeys(PASSWORD, recoveryKey);
    }

    @Test
    void laCleDeSecoursEstLisibleEtSansCaracteresAmbigus() {
        assertThat(recoveryKey).matches("([A-HJKMNP-Z2-9]{5}-){5}[A-HJKMNP-Z2-9]{5}");
    }

    @Test
    void allerRetourPourToutesLesTaillesAutourDesBlocs() throws IOException {
        Random random = new Random(42);
        for (int size : new int[] { 0, 1, 1000, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK + 12345 }) {
            byte[] data = new byte[size];
            random.nextBytes(data);
            Path plain = Files.write(dir.resolve("clair-" + size), data);
            Path encrypted = dir.resolve("chiffre-" + size);
            Path decrypted = dir.resolve("retour-" + size);

            BackupCrypto.encrypt(plain, encrypted, keys);
            assertThat(BackupCrypto.isEncrypted(encrypted)).isTrue();

            byte[] dataKey = BackupCrypto.unwrapWithKeys(BackupCrypto.readHeader(encrypted), keys);
            assertThat(dataKey).isNotNull();
            BackupCrypto.decrypt(encrypted, decrypted, dataKey);
            assertThat(Files.readAllBytes(decrypted)).as("taille %d", size).isEqualTo(data);
        }
    }

    @Test
    void leMotDePasseOuLaCleDeSecoursOuvrentLArchive() throws IOException {
        Path encrypted = encryptSample(5000);
        Header header = BackupCrypto.readHeader(encrypted);
        byte[] dataKey = BackupCrypto.unwrapWithKeys(header, keys);

        assertThat(BackupCrypto.unwrapWithSecret(header, PASSWORD)).isEqualTo(dataKey);
        assertThat(BackupCrypto.unwrapWithSecret(header, recoveryKey)).isEqualTo(dataKey);
        // Saisie tolérante : minuscules, espaces à la place des tirets.
        assertThat(BackupCrypto.unwrapWithSecret(header, recoveryKey.toLowerCase().replace('-', ' ')))
                .isEqualTo(dataKey);
    }

    @Test
    void unMauvaisSecretNOuvreRien() throws IOException {
        Header header = BackupCrypto.readHeader(encryptSample(5000));

        assertThat(BackupCrypto.unwrapWithSecret(header, "mauvais mot de passe")).isNull();
        Keys otherInstallation = BackupCrypto.newKeys("autre mot de passe", BackupCrypto.newRecoveryKey());
        assertThat(BackupCrypto.unwrapWithKeys(header, otherInstallation)).isNull();
    }

    @Test
    void uneArchiveModifieeTronqueeOuRallongeeEstRefusee() throws IOException {
        Path encrypted = encryptSample(3 * CHUNK + 12345);
        byte[] dataKey = BackupCrypto.unwrapWithKeys(BackupCrypto.readHeader(encrypted), keys);
        byte[] original = Files.readAllBytes(encrypted);
        Path out = dir.resolve("sortie");

        byte[] flipped = original.clone();
        flipped[original.length / 2] ^= 1;
        assertRefused(flipped, dataKey, out);

        assertRefused(Arrays.copyOf(original, original.length - 1), dataKey, out);
        assertRefused(Arrays.copyOf(original, original.length + 5), dataKey, out);

        // Troncature nette à une frontière de bloc : le dernier bloc entier est retiré.
        int headerLength = 4 + 1 + 4 + 16 + 12 + 48 + 16 + 12 + 48 + 8;
        int fullChunk = 4 + CHUNK + 16;
        assertRefused(Arrays.copyOf(original, headerLength + 3 * fullChunk), dataKey, out);

        // Deux blocs échangés.
        byte[] swapped = original.clone();
        System.arraycopy(original, headerLength + fullChunk, swapped, headerLength, fullChunk);
        System.arraycopy(original, headerLength, swapped, headerLength + fullChunk, fullChunk);
        assertRefused(swapped, dataKey, out);

        // Mauvaise clé d'archive.
        assertRefused(original, new byte[32], out);
    }

    @Test
    void unFichierOrdinaireNEstPasPrisPourUneArchiveChiffree() throws IOException {
        Path zip = Files.write(dir.resolve("simple.zip"), new byte[] { 'P', 'K', 3, 4, 0, 0 });
        assertThat(BackupCrypto.isEncrypted(zip)).isFalse();
        assertThatThrownBy(() -> BackupCrypto.readHeader(zip)).isInstanceOf(IOException.class);
    }

    private Path encryptSample(int size) throws IOException {
        byte[] data = new byte[size];
        new Random(7).nextBytes(data);
        Path plain = Files.write(dir.resolve("echantillon"), data);
        Path encrypted = dir.resolve("echantillon.gcbak");
        BackupCrypto.encrypt(plain, encrypted, keys);
        return encrypted;
    }

    private void assertRefused(byte[] content, byte[] dataKey, Path out) throws IOException {
        Path file = Files.write(dir.resolve("altere.gcbak"), content);
        assertThatThrownBy(() -> BackupCrypto.decrypt(file, out, dataKey)).isInstanceOf(IOException.class);
    }
}
