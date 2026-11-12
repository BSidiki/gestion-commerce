package com.gestioncommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogStockIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Autowired
    JdbcClient jdbc;

    @Test
    void sqliteEstConfigurePourResisterAuxCoupures() {
        assertThat(jdbc.sql("PRAGMA journal_mode").query(String.class).single()).isEqualToIgnoringCase("wal");
        assertThat(jdbc.sql("PRAGMA synchronous").query(Integer.class).single()).isEqualTo(2); // FULL
        assertThat(jdbc.sql("PRAGMA foreign_keys").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void receptionsEtAjustementsTiennentLeStockEtLeCoutMoyen() throws Exception {
        JsonNode product = createProduct("""
                {
                  "name": "Savon de Marseille",
                  "baseUnit": "pièce",
                  "alertThreshold": 50,
                  "packagings": [
                    {"name": "Unité", "factor": 1, "barcode": "6001234567890",
                     "prices": [{"level": "DETAIL", "price": 300, "floorPrice": 250}]},
                    {"name": "Carton", "factor": 24,
                     "prices": [{"level": "GROS", "price": 6500, "floorPrice": 6000}]}
                  ]
                }
                """);
        String productId = product.at("/product/id").asText();
        String cartonId = product.at("/packagings/1/id").asText();
        assertThat(product.at("/packagings/1/name").asText()).isEqualTo("Carton");

        // 2 cartons pour 10 000 F : 48 pièces à 208,333 F l'une.
        receive(productId, cartonId, 2, 10_000)
                .andExpect(jsonPath("$.stockQty").value(48))
                .andExpect(jsonPath("$.avgCostMilli").value(208_333));

        // 1 carton pour 6 000 F : le coût moyen pondéré monte à 222,222 F.
        receive(productId, cartonId, 1, 6_000)
                .andExpect(jsonPath("$.stockQty").value(72))
                .andExpect(jsonPath("$.avgCostMilli").value(222_222));

        // 5 pièces cassées : le stock baisse, le coût moyen ne bouge pas.
        mvc.perform(post("/api/stock/adjustments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"productId": "%s", "quantityDelta": -5, "reason": "CASSE", "note": "carton tombé"}
                        """.formatted(productId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockQty").value(67))
                .andExpect(jsonPath("$.avgCostMilli").value(222_222));

        mvc.perform(get("/api/products/{id}/movements", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("AJUSTEMENT"))
                .andExpect(jsonPath("$[0].reason").value("CASSE"))
                .andExpect(jsonPath("$[0].stockAfter").value(67));

        // Le cache du produit est égal au cumul des mouvements.
        long cumul = jdbc.sql("SELECT SUM(quantity) FROM stock_movement WHERE product_id = ?")
                .param(productId).query(Long.class).single();
        assertThat(cumul).isEqualTo(67);

        // 67 > seuil de 50 : pas d'alerte.
        mvc.perform(get("/api/products").param("lowStock", "true"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(productId)).isEmpty());
    }

    @Test
    void unProduitSansStockApparaitDansLesAlertes() throws Exception {
        JsonNode product = createProduct("""
                {"name": "Riz parfumé", "baseUnit": "gramme", "alertThreshold": 5000,
                 "packagings": [{"name": "Gramme", "factor": 1}, {"name": "Sac 25 kg", "factor": 25000}]}
                """);
        mvc.perform(get("/api/products").param("lowStock", "true"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(product.at("/product/id").asText())).isNotEmpty());
    }

    @Test
    void leProduitExigeUnConditionnementDeBase() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Huile", "baseUnit": "bouteille",
                         "packagings": [{"name": "Carton", "factor": 12}]}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void lePrixPlancherNeDepassePasLePrixDeVente() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Sucre", "baseUnit": "paquet",
                         "packagings": [{"name": "Paquet", "factor": 1,
                           "prices": [{"level": "DETAIL", "price": 500, "floorPrice": 600}]}]}
                        """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unCodeBarresNeSertQuUneFois() throws Exception {
        String body = """
                {"name": "%s", "baseUnit": "boîte",
                 "packagings": [{"name": "Boîte", "factor": 1, "barcode": "6009999999999"}]}
                """;
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body.formatted("Lait A")))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body.formatted("Lait B")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void uneRequeteIncompleteEstRefusee() throws Exception {
        mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "", "baseUnit": "pièce", "packagings": []}
                        """))
                .andExpect(status().isBadRequest());
    }

    private JsonNode createProduct(String body) throws Exception {
        String response = mvc.perform(post("/api/products").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private org.springframework.test.web.servlet.ResultActions receive(String productId, String packagingId,
            long quantity, long totalCost) throws Exception {
        return mvc.perform(post("/api/stock/receptions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"productId": "%s", "packagingId": "%s", "quantity": %d, "totalCost": %d}
                        """.formatted(productId, packagingId, quantity, totalCost)))
                .andExpect(status().isCreated());
    }
}
