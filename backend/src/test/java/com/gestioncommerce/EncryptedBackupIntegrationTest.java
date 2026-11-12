package com.gestioncommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sauvegardes protégées par mot de passe : ce PC les ouvre seul tant qu'il en a la clé ;
 * après un changement de mot de passe, les anciennes demandent leur mot de passe ou leur clé de secours.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EncryptedBackupIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-chiffrement");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void sauvegardesProtegeesParMotDePasse() throws Exception {
        Path usb = Files.createTempDirectory("gestion-test-cle-usb-chiffree");

        mvc.perform(get("/api/backups")).andExpect(jsonPath("$.encryptionEnabled").value(false));

        // Mot de passe trop court : refusé.
        enableEncryption("court").andExpect(status().isBadRequest());

        // --- Activation : la clé de secours est renvoyée une seule fois ---
        String firstRecoveryKey = json.readTree(enableEncryption("premier mot de passe")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/recoveryKey").asText();
        assertThat(firstRecoveryKey).matches("([A-Z2-9]{5}-){5}[A-Z2-9]{5}");
        mvc.perform(get("/api/backups")).andExpect(jsonPath("$.encryptionEnabled").value(true));

        createProduct("Savon").andExpect(status().isCreated());

        // --- La sauvegarde est chiffrée : ni zip lisible, ni nom de produit en clair ---
        Path firstArchive = backupTo(usb);
        assertThat(firstArchive.getFileName().toString()).endsWith(".gcbak");
        byte[] content = Files.readAllBytes(firstArchive);
        assertThat(new String(content, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("GCBK");
        assertThat(new String(content, StandardCharsets.ISO_8859_1)).doesNotContain("Savon");

        mvc.perform(get("/api/backups/files").param("directory", usb.toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].encrypted").value(true))
                .andExpect(jsonPath("$[0].needsSecret").value(false));

        // --- Ce PC a la clé : la restauration ne demande rien ---
        createProduct("Huile").andExpect(status().isCreated());
        restore(firstArchive, null).andExpect(status().isOk());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(1));

        // --- Changement de mot de passe : l'ancienne archive ne s'ouvre plus toute seule ---
        String secondRecoveryKey = json.readTree(enableEncryption("second mot de passe")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/recoveryKey").asText();
        assertThat(secondRecoveryKey).isNotEqualTo(firstRecoveryKey);

        mvc.perform(get("/api/backups/files").param("directory", usb.toString()))
                .andExpect(jsonPath("$[0].needsSecret").value(true));

        createProduct("Sucre").andExpect(status().isCreated());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));

        restore(firstArchive, null).andExpect(status().isUnprocessableEntity());
        restore(firstArchive, "mauvais mot de passe").andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));

        // --- L'ancien mot de passe ouvre l'ancienne archive ---
        restore(firstArchive, "premier mot de passe").andExpect(status().isOk());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(1));

        // --- Sa clé de secours aussi, même saisie en minuscules et sans tirets ---
        createProduct("Riz").andExpect(status().isCreated());
        restore(firstArchive, firstRecoveryKey.toLowerCase().replace("-", " ")).andExpect(status().isOk());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(1));

        // --- Après ces restaurations, la protection actuelle (second mot de passe) est conservée ---
        mvc.perform(get("/api/backups")).andExpect(jsonPath("$.encryptionEnabled").value(true));
        Path secondArchive = backupTo(usb);
        mvc.perform(get("/api/backups/files").param("directory", usb.toString()))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.name == '%s')].needsSecret"
                        .formatted(secondArchive.getFileName())).value(false))
                .andExpect(jsonPath("$[?(@.name == '%s')].needsSecret"
                        .formatted(firstArchive.getFileName())).value(true));
    }

    private ResultActions enableEncryption(String password) throws Exception {
        return postJson("/api/backups/encryption", json.writeValueAsString(Map.of("password", password)));
    }

    private Path backupTo(Path directory) throws Exception {
        JsonNode backup = json.readTree(postJson("/api/backups", json.writeValueAsString(
                Map.of("directory", directory.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REUSSIE"))
                .andReturn().getResponse().getContentAsString());
        return Path.of(backup.at("/path").asText());
    }

    private ResultActions restore(Path archive, String secret) throws Exception {
        Map<String, String> body = new HashMap<>();
        body.put("path", archive.toString());
        if (secret != null) {
            body.put("secret", secret);
        }
        return postJson("/api/backups/restore", json.writeValueAsString(body));
    }

    private ResultActions createProduct(String name) throws Exception {
        return postJson("/api/products", """
                {"name": "%s", "baseUnit": "pièce", "packagings": [{"name": "pièce", "factor": 1}]}
                """.formatted(name));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
