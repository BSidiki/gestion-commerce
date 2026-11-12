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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestioncommerce.auth.AuthController;

import jakarta.servlet.http.Cookie;

/**
 * Comptes et droits : accès libre avant le premier compte, puis connexion obligatoire ;
 * ce que le vendeur peut et ne peut pas faire ; ce que seul le propriétaire peut faire.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-comptes");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void comptesConnexionEtDroits() throws Exception {
        // --- Avant le premier compte : accès libre, et l'interface est invitée à créer le propriétaire ---
        mvc.perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.setupRequired").value(true))
                .andExpect(jsonPath("$.user").isEmpty());
        mvc.perform(get("/api/products")).andExpect(status().isOk());

        // Mot de passe trop court, nom d'utilisateur invalide : refusés.
        postJson(null, "/api/auth/setup", Map.of("username", "awa", "displayName", "Awa", "password", "123"))
                .andExpect(status().isUnprocessableEntity());
        postJson(null, "/api/auth/setup", Map.of("username", "a b", "displayName", "Awa", "password", "secret-awa"))
                .andExpect(status().isUnprocessableEntity());

        // --- Création du propriétaire : il est connecté dans la foulée ---
        Cookie owner = sessionOf(postJson(null, "/api/auth/setup",
                Map.of("username", "awa", "displayName", "Awa Ouédraogo", "password", "secret-awa"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("PROPRIETAIRE"))
                .andReturn());
        assertThat(owner.isHttpOnly()).isTrue();

        // Le compte du propriétaire ne se crée qu'une fois.
        postJson(null, "/api/auth/setup", Map.of("username", "autre", "displayName", "X", "password", "secret-x"))
                .andExpect(status().isUnprocessableEntity());

        // --- Désormais, sans session, tout est fermé sauf de quoi se connecter ---
        mvc.perform(get("/api/products")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.setupRequired").value(false))
                .andExpect(jsonPath("$.user").isEmpty());
        mvc.perform(get("/api/products").cookie(new Cookie(AuthController.SESSION_COOKIE, "jeton-invente")))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/auth/me").cookie(owner))
                .andExpect(jsonPath("$.user.username").value("awa"))
                .andExpect(jsonPath("$.user.displayName").value("Awa Ouédraogo"));

        // --- Le propriétaire prépare la boutique ---
        JsonNode product = json.readTree(postJson(owner, "/api/products", Map.of(
                "name", "Huile 1 L", "baseUnit", "bouteille",
                "packagings", new Object[] { Map.of("name", "bouteille", "factor", 1, "prices",
                        new Object[] { Map.of("level", "DETAIL", "price", 1200, "floorPrice", 1100) }) }))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String productId = product.at("/product/id").asText();
        String packagingId = product.at("/packagings/0/id").asText();

        String sellerId = json.readTree(postJson(owner, "/api/users",
                Map.of("username", "moussa", "displayName", "Moussa", "role", "VENDEUR", "password", "secret-moussa"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        postJson(owner, "/api/users",
                Map.of("username", "MOUSSA", "displayName", "Doublon", "role", "VENDEUR", "password", "secret-moussa"))
                .andExpect(status().isUnprocessableEntity());

        // --- Connexion du vendeur ---
        login("moussa", "mauvais").andExpect(status().isUnauthorized());
        Cookie seller = sessionOf(login("Moussa", "secret-moussa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("VENDEUR"))
                .andReturn());

        // --- Ce que le vendeur peut faire ---
        mvc.perform(get("/api/catalog/sellable").cookie(seller)).andExpect(status().isOk());
        String saleId = json.readTree(sale(seller, packagingId, 1200)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        postJson(seller, "/api/stock/receptions",
                Map.of("productId", productId, "packagingId", packagingId, "quantity", 10, "totalCost", 9000))
                .andExpect(status().isCreated());
        postJson(seller, "/api/expenses", Map.of("category", "Transport", "amount", 500, "method", "ESPECES"))
                .andExpect(status().isCreated());

        // --- Ce qu'il ne peut pas faire ---
        sale(seller, packagingId, 1000).andExpect(status().isUnprocessableEntity()); // sous le prix plancher
        postJson(seller, "/api/sales/" + saleId + "/cancel", Map.of("reason", "Erreur"))
                .andExpect(status().isForbidden());
        postJson(seller, "/api/products", Map.of("name", "X", "baseUnit", "u",
                "packagings", new Object[] { Map.of("name", "u", "factor", 1) }))
                .andExpect(status().isForbidden());
        postJson(seller, "/api/stock/adjustments",
                Map.of("productId", productId, "quantityDelta", -1, "reason", "CASSE"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/reports/daily").cookie(seller)).andExpect(status().isForbidden());
        mvc.perform(get("/api/users").cookie(seller)).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").cookie(seller)).andExpect(status().isForbidden());
        postJson(seller, "/api/users/" + sellerId + "/password", Map.of("password", "nouveau-secret"))
                .andExpect(status().isForbidden());

        // Il change son propre mot de passe, à condition de donner l'ancien.
        postJson(seller, "/api/auth/password", Map.of("currentPassword", "faux", "newPassword", "secret-moussa-2"))
                .andExpect(status().isUnprocessableEntity());
        postJson(seller, "/api/auth/password",
                Map.of("currentPassword", "secret-moussa", "newPassword", "secret-moussa-2"))
                .andExpect(status().isNoContent());
        login("moussa", "secret-moussa").andExpect(status().isUnauthorized());
        login("moussa", "secret-moussa-2").andExpect(status().isOk());

        // --- La secrétaire : comme le vendeur, plus le catalogue et les marges ---
        postJson(owner, "/api/users",
                Map.of("username", "fatou", "displayName", "Fatou", "role", "SECRETAIRE", "password", "secret-fatou"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("SECRETAIRE"));
        postJson(owner, "/api/users",
                Map.of("username", "inconnu", "displayName", "X", "role", "GERANT", "password", "secret-x"))
                .andExpect(status().isUnprocessableEntity());
        Cookie secretary = sessionOf(login("fatou", "secret-fatou").andExpect(status().isOk()).andReturn());

        sale(secretary, packagingId, 1200).andExpect(status().isCreated());
        postJson(secretary, "/api/products", Map.of("name", "Sucre", "baseUnit", "paquet",
                "packagings", new Object[] { Map.of("name", "paquet", "factor", 1) }))
                .andExpect(status().isCreated());
        postJson(secretary, "/api/categories", Map.of("name", "Épicerie")).andExpect(status().isCreated());
        mvc.perform(get("/api/reports/daily").cookie(secretary)).andExpect(status().isOk());

        sale(secretary, packagingId, 1000).andExpect(status().isUnprocessableEntity()); // sous le prix plancher
        postJson(secretary, "/api/sales/" + saleId + "/cancel", Map.of("reason", "Erreur"))
                .andExpect(status().isForbidden());
        postJson(secretary, "/api/stock/adjustments",
                Map.of("productId", productId, "quantityDelta", -1, "reason", "CASSE"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/users").cookie(secretary)).andExpect(status().isForbidden());
        mvc.perform(get("/api/audit").cookie(secretary)).andExpect(status().isForbidden());

        // --- Ce que seul le propriétaire peut faire ---
        sale(owner, packagingId, 1000).andExpect(status().isCreated()); // sous le plancher : accordé, et journalisé
        postJson(owner, "/api/sales/" + saleId + "/cancel", Map.of("reason", "Erreur de saisie"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANNULEE"));
        postJson(owner, "/api/stock/adjustments",
                Map.of("productId", productId, "quantityDelta", -1, "reason", "CASSE"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/reports/daily").cookie(owner)).andExpect(status().isOk());

        String audit = mvc.perform(get("/api/audit").cookie(owner))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(audit).contains("VENTE_SOUS_PLANCHER", "VENTE_ANNULEE", "STOCK_AJUSTE", "COMPTE_CREE",
                "MOT_DE_PASSE_CHANGE");
        assertThat(json.readTree(audit).get(0).at("/username").asText()).isEqualTo("awa");

        // --- Comptes : on ne désactive pas le dernier propriétaire ; un compte désactivé perd sa session ---
        String ownerId = json.readTree(mvc.perform(get("/api/auth/me").cookie(owner))
                .andReturn().getResponse().getContentAsString()).at("/user/id").asText();
        postJson(owner, "/api/users/" + ownerId + "/active", Map.of("active", false))
                .andExpect(status().isUnprocessableEntity());
        postJson(owner, "/api/users/" + sellerId + "/active", Map.of("active", false))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/catalog/sellable").cookie(seller)).andExpect(status().isUnauthorized());
        login("moussa", "secret-moussa-2").andExpect(status().isUnauthorized());

        // --- Cinq essais ratés bloquent le compte une minute, même avec le bon mot de passe ---
        for (int i = 0; i < 5; i++) {
            login("awa", "mauvais-" + i).andExpect(status().isUnauthorized());
        }
        login("awa", "secret-awa").andExpect(status().isTooManyRequests());

        // --- Déconnexion ---
        mvc.perform(post("/api/auth/logout").cookie(owner)).andExpect(status().isNoContent());
        mvc.perform(get("/api/products").cookie(owner)).andExpect(status().isUnauthorized());
    }

    private ResultActions sale(Cookie session, String packagingId, long unitPrice) throws Exception {
        return postJson(session, "/api/sales", Map.of(
                "idempotencyKey", UUID.randomUUID().toString(),
                "items", new Object[] { Map.of("packagingId", packagingId, "quantity", 1, "unitPrice", unitPrice) },
                "payments", new Object[] { Map.of("method", "ESPECES", "amount", unitPrice) }));
    }

    private ResultActions login(String username, String password) throws Exception {
        return postJson(null, "/api/auth/login", Map.of("username", username, "password", password));
    }

    private ResultActions postJson(Cookie session, String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body));
        if (session != null) {
            request.cookie(session);
        }
        return mvc.perform(request);
    }

    private static Cookie sessionOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthController.SESSION_COOKIE);
        assertThat(cookie).as("cookie de session").isNotNull();
        return cookie;
    }
}
