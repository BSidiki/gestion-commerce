package com.gestioncommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void leLanceurReconnaitLeLogicielEtLesPagesRenvoientLInterface() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.app").value(Application.APP_NAME));

        // Un lien direct vers une page de l'interface est servi par l'interface elle-même.
        mvc.perform(get("/produits/nouveau"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/index.html"));
        mvc.perform(get("/cloture"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .forwardedUrl("/index.html"));
    }

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
    void unProduitSeModifieSansFausserLeStock() throws Exception {
        JsonNode product = createProduct("""
                {
                  "name": "Lait concentré",
                  "baseUnit": "boîte",
                  "alertThreshold": 10,
                  "packagings": [
                    {"name": "boîte", "factor": 1, "barcode": "6111000000011",
                     "prices": [{"level": "DETAIL", "price": 500, "floorPrice": 450}]},
                    {"name": "Carton", "factor": 48,
                     "prices": [{"level": "GROS", "price": 22000, "floorPrice": 21000}]}
                  ]
                }
                """);
        String productId = product.at("/product/id").asText();
        String unitId = product.at("/packagings/0/id").asText();
        String cartonId = product.at("/packagings/1/id").asText();
        mvc.perform(post("/api/stock/receptions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"productId": "%s", "packagingId": "%s", "quantity": 1, "totalCost": 19200}
                        """.formatted(productId, cartonId)))
                .andExpect(status().isCreated());

        // Nouveau nom, nouveau seuil, prix de détail relevé, code-barres changé, et un conditionnement ajouté.
        // Le carton n'est pas mentionné : il reste tel quel.
        mvc.perform(put("/api/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "name": "Lait concentré sucré",
                          "baseUnit": "boîte",
                          "alertThreshold": 24,
                          "packagings": [
                            {"id": "%s", "name": "boîte", "factor": 1, "barcode": "6111000000028",
                             "prices": [{"level": "DETAIL", "price": 550, "floorPrice": 500}]},
                            {"name": "Paquet de 6", "factor": 6,
                             "prices": [{"level": "DEMI_GROS", "price": 3000, "floorPrice": 2900}]}
                          ]
                        }
                        """.formatted(unitId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.name").value("Lait concentré sucré"))
                .andExpect(jsonPath("$.product.alertThreshold").value(24))
                // Le stock et le coût moyen ne bougent pas.
                .andExpect(jsonPath("$.product.stockQty").value(48))
                .andExpect(jsonPath("$.product.avgCostMilli").value(400_000))
                .andExpect(jsonPath("$.packagings.length()").value(3))
                .andExpect(jsonPath("$.packagings[0].prices[0].price").value(550))
                .andExpect(jsonPath("$.packagings[0].barcodes[0]").value("6111000000028"))
                .andExpect(jsonPath("$.packagings[1].name").value("Paquet de 6"))
                .andExpect(jsonPath("$.packagings[2].name").value("Carton"))
                .andExpect(jsonPath("$.packagings[2].prices[0].price").value(22000));

        // Refusé : changer le contenu d'un conditionnement existant.
        mvc.perform(put("/api/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Lait concentré sucré", "baseUnit": "boîte", "alertThreshold": 24,
                         "packagings": [{"id": "%s", "name": "Carton", "factor": 24}]}
                        """.formatted(cartonId)))
                .andExpect(status().isUnprocessableEntity());
        // Refusé : un nom de conditionnement déjà pris, un conditionnement d'un autre produit.
        mvc.perform(put("/api/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Lait concentré sucré", "baseUnit": "boîte", "alertThreshold": 24,
                         "packagings": [{"name": "carton", "factor": 12}]}
                        """))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/products/{id}", productId).contentType(MediaType.APPLICATION_JSON).content("""
                        {"name": "Lait concentré sucré", "baseUnit": "boîte", "alertThreshold": 24,
                         "packagings": [{"id": "inconnu", "name": "Sachet", "factor": 1}]}
                        """))
                .andExpect(status().isUnprocessableEntity());
        // Les refus n'ont rien changé : toujours trois conditionnements, le carton toujours à 48.
        mvc.perform(get("/api/products/{id}", productId))
                .andExpect(jsonPath("$.packagings.length()").value(3))
                .andExpect(jsonPath("$.packagings[2].factor").value(48));

        // Désactivé : absent de la liste et de la caisse, mais toujours consultable.
        mvc.perform(post("/api/products/{id}/active", productId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.active").value(false));
        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(productId)).isEmpty());
        mvc.perform(get("/api/catalog/sellable"))
                .andExpect(jsonPath("$[?(@.product.id == '%s')]".formatted(productId)).isEmpty());
        mvc.perform(get("/api/products").param("includeInactive", "true"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(productId)).isNotEmpty());

        // Réactivé : de retour partout.
        mvc.perform(post("/api/products/{id}/active", productId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/products"))
                .andExpect(jsonPath("$[?(@.id == '%s')]".formatted(productId)).isNotEmpty());
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
