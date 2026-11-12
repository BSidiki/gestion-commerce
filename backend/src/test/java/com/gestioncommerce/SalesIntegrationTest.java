package com.gestioncommerce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
 * Une journée de boutique de bout en bout : réception, vente en partie à crédit,
 * double clic, refus, remboursement, rapport du jour, annulation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SalesIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-ventes");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void uneJourneeDeVente() throws Exception {
        // --- Mise en place : un savon vendu à l'unité et au carton, 48 pièces en stock ---
        JsonNode product = postJson("/api/products", """
                {
                  "name": "Savon de Marseille",
                  "baseUnit": "pièce",
                  "packagings": [
                    {"name": "pièce", "factor": 1,
                     "prices": [{"level": "DETAIL", "price": 300, "floorPrice": 250}]},
                    {"name": "Carton", "factor": 24,
                     "prices": [{"level": "GROS", "price": 6500, "floorPrice": 6000}]}
                  ]
                }
                """);
        String productId = product.at("/product/id").asText();
        String unitId = product.at("/packagings/0/id").asText();
        String cartonId = product.at("/packagings/1/id").asText();

        // 2 cartons pour 10 000 F : coût moyen de 208,333 F la pièce.
        postJson("/api/stock/receptions", """
                {"productId": "%s", "packagingId": "%s", "quantity": 2, "totalCost": 10000}
                """.formatted(productId, cartonId));

        String customerId = postJson("/api/customers", """
                {"name": "Awa Ouédraogo", "phone": "70 00 00 00"}
                """).at("/id").asText();

        // --- Vente : 1 carton négocié à 6 200 F + 3 pièces à 300 F = 7 100 F, 5 000 F payés ---
        String key = UUID.randomUUID().toString();
        String saleBody = """
                {
                  "idempotencyKey": "%s",
                  "customerId": "%s",
                  "items": [
                    {"packagingId": "%s", "quantity": 1, "unitPrice": 6200},
                    {"packagingId": "%s", "quantity": 3, "unitPrice": 300}
                  ],
                  "payments": [{"method": "ESPECES", "amount": 5000}]
                }
                """.formatted(key, customerId, cartonId, unitId);

        JsonNode sale = json.readTree(postSale(saleBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.total").value(7100))
                .andExpect(jsonPath("$.paid").value(5000))
                .andExpect(jsonPath("$.credit").value(2100))
                .andExpect(jsonPath("$.status").value("VALIDEE"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].listPrice").value(6500))
                .andReturn().getResponse().getContentAsString());
        String saleId = sale.at("/id").asText();

        assertThat(stockOf(productId)).isEqualTo(21); // 48 - 24 - 3
        assertThat(balanceOf(customerId)).isEqualTo(2100);

        // --- Double clic : la même validation ne crée pas une seconde vente ---
        postSale(saleBody)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(saleId));
        assertThat(stockOf(productId)).isEqualTo(21);
        assertThat(balanceOf(customerId)).isEqualTo(2100);

        // --- Refus : aucun ne doit laisser de trace ---
        postSale(simpleSale(null, unitId, 1, 200, 200)) // sous le prix plancher de 250 F
                .andExpect(status().isUnprocessableEntity());
        postSale(simpleSale(null, unitId, 1, 300, 0)) // crédit sans client
                .andExpect(status().isUnprocessableEntity());
        postSale(simpleSale(null, unitId, 1, 300, 500)) // encaissé plus que le total
                .andExpect(status().isUnprocessableEntity());
        assertThat(stockOf(productId)).isEqualTo(21);

        // --- Remboursement partiel en Mobile Money ---
        mvc.perform(post("/api/customers/{id}/repayments", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 1000, "method": "MOBILE_MONEY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.balance").value(1100))
                .andExpect(jsonPath("$.ledger.length()").value(2));

        // On ne rembourse pas plus que ce qui est dû.
        mvc.perform(post("/api/customers/{id}/repayments", customerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount": 5000, "method": "ESPECES"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(get("/api/customers").param("withDebt", "true"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].balance").value(1100));

        // --- Rapport du jour ---
        // Coût des marchandises : 27 pièces x 208,333 F = 5 625 F ; marge = 7 100 - 5 625.
        mvc.perform(get("/api/reports/daily"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleCount").value(1))
                .andExpect(jsonPath("$.revenue").value(7100))
                .andExpect(jsonPath("$.costOfGoods").value(5625))
                .andExpect(jsonPath("$.grossMargin").value(1475))
                .andExpect(jsonPath("$.cash").value(5000))
                .andExpect(jsonPath("$.mobileMoney").value(1000))
                .andExpect(jsonPath("$.creditGranted").value(2100))
                .andExpect(jsonPath("$.repaymentsReceived").value(1000));

        mvc.perform(get("/api/sales"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(saleId));

        // --- Annulation : le stock revient, le crédit de la vente est retiré du compte client ---
        mvc.perform(post("/api/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Erreur de saisie"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANNULEE"))
                .andExpect(jsonPath("$.cancelReason").value("Erreur de saisie"));

        assertThat(stockOf(productId)).isEqualTo(48);
        // 1 100 F dus - 2 100 F de crédit annulé : le client a un avoir de 1 000 F.
        assertThat(balanceOf(customerId)).isEqualTo(-1000);

        mvc.perform(post("/api/sales/{id}/cancel", saleId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "Encore"}
                                """))
                .andExpect(status().isUnprocessableEntity());

        // La vente annulée sort du rapport ; le remboursement déjà reçu y reste.
        mvc.perform(get("/api/reports/daily"))
                .andExpect(jsonPath("$.saleCount").value(0))
                .andExpect(jsonPath("$.revenue").value(0))
                .andExpect(jsonPath("$.cash").value(0))
                .andExpect(jsonPath("$.mobileMoney").value(1000));

        // Cinq mouvements : une réception, deux sorties de vente et leurs deux annulations.
        mvc.perform(get("/api/products/{id}/movements", productId))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].type").value("ANNULATION"));
    }

    private String simpleSale(String customerId, String packagingId, long quantity, long unitPrice, long paid) {
        String customer = customerId == null ? "null" : "\"" + customerId + "\"";
        String payments = paid > 0 ? "[{\"method\": \"ESPECES\", \"amount\": " + paid + "}]" : "[]";
        return """
                {"idempotencyKey": "%s", "customerId": %s,
                 "items": [{"packagingId": "%s", "quantity": %d, "unitPrice": %d}],
                 "payments": %s}
                """.formatted(UUID.randomUUID(), customer, packagingId, quantity, unitPrice, payments);
    }

    private ResultActions postSale(String body) throws Exception {
        return mvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode postJson(String path, String body) throws Exception {
        String response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private long stockOf(String productId) throws Exception {
        String response = mvc.perform(get("/api/products/{id}", productId))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/product/stockQty").asLong();
    }

    private long balanceOf(String customerId) throws Exception {
        String response = mvc.perform(get("/api/customers/{id}", customerId))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).at("/customer/balance").asLong();
    }
}
