package com.gestioncommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/** Sauvegarde sur un support externe, perte de données simulée, restauration, puis reprise du travail. */
@SpringBootTest
@AutoConfigureMockMvc
class BackupIntegrationTest {

    static Path dataDir;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        dataDir = Files.createTempDirectory("gestion-test-sauvegarde");
        registry.add("app.data-dir", () -> dataDir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void sauvegarderPuisRestaurer() throws Exception {
        // Fait office de clé USB : un dossier hors du dossier de données.
        Path usb = Files.createTempDirectory("gestion-test-cle-usb");

        // Au départ : jamais de sauvegarde, donc un rappel.
        mvc.perform(get("/api/backups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status.reminder").value(true))
                .andExpect(jsonPath("$.history.length()").value(0))
                .andExpect(jsonPath("$.destinations[0].local").value(true));

        createProduct("Savon").andExpect(status().isCreated());

        // --- Sauvegarde sur la « clé USB » ---
        JsonNode backup = json.readTree(postJson("/api/backups", json.writeValueAsString(
                Map.of("directory", usb.toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REUSSIE"))
                .andExpect(jsonPath("$.kind").value("MANUELLE"))
                .andExpect(jsonPath("$.external").value(true))
                .andReturn().getResponse().getContentAsString());
        Path archive = Path.of(backup.at("/path").asText());
        assertThat(archive).exists().hasParent(usb);
        assertThat(Files.size(archive)).isGreaterThan(0);

        mvc.perform(get("/api/backups"))
                .andExpect(jsonPath("$.status.reminder").value(false))
                .andExpect(jsonPath("$.history.length()").value(1));

        mvc.perform(get("/api/backups/files").param("directory", usb.toString()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value(archive.getFileName().toString()));

        // --- Travail fait après la sauvegarde : il disparaîtra à la restauration ---
        createProduct("Huile").andExpect(status().isCreated());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));

        // --- Fichiers refusés : inexistant, puis qui n'est pas une sauvegarde ---
        restore(usb.resolve("absent.zip")).andExpect(status().isUnprocessableEntity());
        Path fake = Files.writeString(usb.resolve("faux.zip"), "ceci n'est pas une sauvegarde");
        restore(fake).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));

        // --- Restauration ---
        JsonNode restored = json.readTree(restore(archive)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        Path safetyCopy = Path.of(restored.at("/safetyCopyPath").asText());
        assertThat(safetyCopy).exists();
        assertThat(safetyCopy.startsWith(dataDir)).isTrue();

        // On retrouve l'état de la sauvegarde : un seul produit.
        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Savon"));

        // L'historique garde la trace de la copie de sécurité et de la restauration.
        mvc.perform(get("/api/backups"))
                .andExpect(jsonPath("$.history[0].kind").value("RESTAURATION"))
                .andExpect(jsonPath("$.history[1].kind").value("AVANT_RESTAURATION"));

        // --- Le logiciel reste utilisable après la restauration ---
        createProduct("Sucre").andExpect(status().isCreated());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));

        // --- La copie de sécurité permet de revenir à l'état d'avant la restauration ---
        restore(safetyCopy).andExpect(status().isOk());
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/products").param("lowStock", "false"))
                .andExpect(jsonPath("$[?(@.name == 'Huile')]").isNotEmpty());

        // --- Une clôture de caisse déclenche une sauvegarde locale automatique ---
        postJson("/api/cash/open", "{\"openingFloat\": 0}").andExpect(status().isCreated());
        postJson("/api/cash/close", "{\"countedCash\": 0}").andExpect(status().isOk());
        mvc.perform(get("/api/backups"))
                .andExpect(jsonPath("$.history[0].kind").value("AUTOMATIQUE"))
                .andExpect(jsonPath("$.history[0].status").value("REUSSIE"))
                .andExpect(jsonPath("$.history[0].external").value(false));
    }

    private ResultActions createProduct(String name) throws Exception {
        return postJson("/api/products", """
                {"name": "%s", "baseUnit": "pièce", "packagings": [{"name": "pièce", "factor": 1}]}
                """.formatted(name));
    }

    private ResultActions restore(Path archive) throws Exception {
        return postJson("/api/backups/restore", json.writeValueAsString(Map.of("path", archive.toString())));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
