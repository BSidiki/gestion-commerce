package com.gestioncommerce;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestioncommerce.license.LicenseCodec;
import com.gestioncommerce.license.LicenseCodec.License;
import com.gestioncommerce.license.LicenseService;

/**
 * Vie d'une installation : essai de 15 jours, lecture seule, activation d'une licence,
 * expiration, tentative de recul de l'horloge, renouvellement.
 * Le temps qui passe est simulé en modifiant les dates notées par le logiciel.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LicenseIntegrationTest {

    static final KeyPair PUBLISHER = newKeyPair();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-licence");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
        registry.add("app.license.public-key",
                () -> Base64.getEncoder().encodeToString(PUBLISHER.getPublic().getEncoded()));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Test
    void essaiLectureSeuleActivationExpirationEtRenouvellement() throws Exception {
        LocalDate today = LocalDate.now();

        // --- Premier lancement : 15 jours d'essai, tout fonctionne ---
        String installId = json.readTree(mvc.perform(get("/api/license"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ESSAI"))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.daysLeft").value(15))
                .andReturn().getResponse().getContentAsString()).at("/installId").asText();
        org.assertj.core.api.Assertions.assertThat(installId).matches("[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}");
        createProduct("Savon").andExpect(status().isCreated());

        // --- Dernier jour d'essai : encore utilisable ---
        setting(LicenseService.KEY_FIRST_RUN_ON, today.minusDays(14));
        mvc.perform(get("/api/license"))
                .andExpect(jsonPath("$.state").value("ESSAI"))
                .andExpect(jsonPath("$.daysLeft").value(1));
        createProduct("Huile").andExpect(status().isCreated());

        // --- Seizième jour : lecture seule ---
        setting(LicenseService.KEY_FIRST_RUN_ON, today.minusDays(15));
        mvc.perform(get("/api/license"))
                .andExpect(jsonPath("$.state").value("ESSAI_TERMINE"))
                .andExpect(jsonPath("$.readOnly").value(true));
        createProduct("Sucre").andExpect(status().isForbidden());
        // Tout reste consultable, et la sauvegarde reste possible : les données ne sont pas prises en otage.
        mvc.perform(get("/api/products")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        Path usb = Files.createTempDirectory("gestion-test-licence-usb");
        postJson("/api/backups", json.writeValueAsString(Map.of("directory", usb.toString())))
                .andExpect(status().isCreated());

        // --- Licences refusées ---
        activate("ceci n'est pas une licence").andExpect(status().isUnprocessableEntity());
        activate(license("AAAA-BBBB-CCCC", today.plusMonths(3))).andExpect(status().isUnprocessableEntity());
        activate(license(installId, today.minusDays(1))).andExpect(status().isUnprocessableEntity());
        KeyPair impostor = newKeyPair();
        activate(LicenseCodec.sign(new License(UUID.randomUUID().toString(), "Boutique", installId, today,
                today.plusMonths(3)), impostor.getPrivate())).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/license")).andExpect(jsonPath("$.readOnly").value(true));

        // --- Licence de trois mois : le logiciel repart ---
        activate(license(installId, today.plusMonths(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.readOnly").value(false))
                .andExpect(jsonPath("$.shop").value("Alimentation Wend-Kuni"))
                .andExpect(jsonPath("$.warning").value(false));
        createProduct("Sucre").andExpect(status().isCreated());

        // --- Le temps passe : le logiciel a vu une date postérieure à l'expiration ---
        // Même si l'horloge du PC est ensuite reculée (elle indique aujourd'hui), la licence reste expirée.
        LocalDate later = today.plusMonths(3).plusDays(10);
        setting(LicenseService.KEY_LAST_SEEN_ON, later);
        mvc.perform(get("/api/license"))
                .andExpect(jsonPath("$.state").value("EXPIREE"))
                .andExpect(jsonPath("$.readOnly").value(true));
        createProduct("Riz").andExpect(status().isForbidden());

        // --- Renouvellement ---
        activate(license(installId, later.plusMonths(3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
        createProduct("Riz").andExpect(status().isCreated());

        // --- À moins de 15 jours de l'échéance, un avertissement ---
        setting(LicenseService.KEY_LAST_SEEN_ON, later.plusMonths(3).minusDays(5));
        mvc.perform(get("/api/license"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.daysLeft").value(6))
                .andExpect(jsonPath("$.warning").value(true));
    }

    private String license(String installId, LocalDate expiresOn) throws Exception {
        return LicenseCodec.sign(new License(UUID.randomUUID().toString(), "Alimentation Wend-Kuni", installId,
                LocalDate.now(), expiresOn), PUBLISHER.getPrivate());
    }

    private void setting(String key, LocalDate value) {
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .params(key, value.toString())
                .update();
    }

    private ResultActions activate(String token) throws Exception {
        return postJson("/api/license", json.writeValueAsString(Map.of("license", token)));
    }

    private ResultActions createProduct(String name) throws Exception {
        return postJson("/api/products", """
                {"name": "%s", "baseUnit": "pièce", "packagings": [{"name": "pièce", "factor": 1}]}
                """.formatted(name));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
