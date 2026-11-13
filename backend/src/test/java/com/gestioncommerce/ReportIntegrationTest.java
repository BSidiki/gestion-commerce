package com.gestioncommerce;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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

/** Rapports : activité sur une période, état du stock et produits qui dorment, crédits en attente. */
@SpringBootTest
@AutoConfigureMockMvc
class ReportIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-rapports");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void rapportsDeGestion() throws Exception {
        // --- Deux produits : l'huile se vend, le sel reste en rayon ---
        JsonNode oil = product("Huile 1 L", "bouteille", 1200);
        String oilPackaging = oil.at("/packagings/0/id").asText();
        reception(oil, 20, 18000); // 900 F la bouteille
        reception(product("Sel fin", "sachet", 200), 10, 1000); // 100 F le sachet

        String customerId = json.readTree(postJson("/api/customers", """
                {"name": "Moussa", "phone": "70 11 22 33"}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .at("/id").asText();

        // Deux bouteilles au comptant ; une à crédit (500 F payés, 700 F dus) ; une vente annulée.
        sale(oilPackaging, null, 2, 2400).andExpect(status().isCreated());
        sale(oilPackaging, customerId, 1, 500).andExpect(status().isCreated());
        String cancelledId = json.readTree(sale(oilPackaging, null, 1, 1200)
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        postJson("/api/sales/" + cancelledId + "/cancel", """
                {"reason": "Erreur"}
                """).andExpect(status().isOk());

        postJson("/api/expenses", """
                {"category": "Transport", "amount": 500, "method": "ESPECES"}
                """).andExpect(status().isCreated());
        postJson("/api/expenses", """
                {"category": "Loyer", "amount": 1000, "method": "MOBILE_MONEY"}
                """).andExpect(status().isCreated());
        postJson("/api/customers/" + customerId + "/repayments", """
                {"amount": 200, "method": "ESPECES"}
                """).andExpect(status().isCreated());

        // --- Activité de la période (par défaut : les 30 derniers jours) ---
        // Chiffre d'affaires 3 600 F, coût 3 × 900 = 2 700 F, marge 900 F, dépenses 1 500 F.
        mvc.perform(get("/api/reports/period"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleCount").value(2))
                .andExpect(jsonPath("$.revenue").value(3600))
                .andExpect(jsonPath("$.costOfGoods").value(2700))
                .andExpect(jsonPath("$.grossMargin").value(900))
                .andExpect(jsonPath("$.expenses").value(1500))
                .andExpect(jsonPath("$.estimatedResult").value(-600))
                .andExpect(jsonPath("$.cash").value(3100))
                .andExpect(jsonPath("$.mobileMoney").value(0))
                .andExpect(jsonPath("$.creditGranted").value(700))
                .andExpect(jsonPath("$.repaymentsReceived").value(200))
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].date").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.days[0].revenue").value(3600))
                .andExpect(jsonPath("$.days[0].expenses").value(1500))
                .andExpect(jsonPath("$.topProducts.length()").value(1))
                .andExpect(jsonPath("$.topProducts[0].name").value("Huile 1 L"))
                .andExpect(jsonPath("$.topProducts[0].quantity").value(3))
                .andExpect(jsonPath("$.topProducts[0].revenue").value(3600))
                .andExpect(jsonPath("$.topProducts[0].grossMargin").value(900))
                .andExpect(jsonPath("$.expensesByCategory[0].category").value("Loyer"))
                .andExpect(jsonPath("$.expensesByCategory[0].amount").value(1000))
                .andExpect(jsonPath("$.expensesByCategory[1].category").value("Transport"))
                .andExpect(jsonPath("$.sellers.length()").value(1))
                .andExpect(jsonPath("$.sellers[0].saleCount").value(2));

        // Une période sans activité, puis des dates incohérentes ou trop éloignées.
        LocalDate yesterday = LocalDate.now().minusDays(1);
        mvc.perform(get("/api/reports/period")
                        .param("from", yesterday.minusDays(6).toString()).param("to", yesterday.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleCount").value(0))
                .andExpect(jsonPath("$.revenue").value(0))
                .andExpect(jsonPath("$.days.length()").value(0))
                .andExpect(jsonPath("$.topProducts.length()").value(0));
        mvc.perform(get("/api/reports/period")
                        .param("from", LocalDate.now().toString()).param("to", yesterday.toString()))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/reports/period")
                        .param("from", LocalDate.now().minusYears(2).toString()).param("to", yesterday.toString()))
                .andExpect(status().isUnprocessableEntity());

        // --- Stock : 17 bouteilles à 900 F et 10 sachets à 100 F ; le sel ne s'est jamais vendu ---
        mvc.perform(get("/api/reports/stock"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productCount").value(2))
                .andExpect(jsonPath("$.stockValue").value(16300))
                .andExpect(jsonPath("$.dormantDays").value(30))
                .andExpect(jsonPath("$.dormantCount").value(1))
                .andExpect(jsonPath("$.dormantValue").value(1000))
                .andExpect(jsonPath("$.dormant[0].name").value("Sel fin"))
                .andExpect(jsonPath("$.dormant[0].stockQty").value(10))
                .andExpect(jsonPath("$.dormant[0].lastSoldOn").isEmpty());
        mvc.perform(get("/api/reports/stock").param("dormantDays", "0"))
                .andExpect(status().isUnprocessableEntity());

        // --- Crédits : Moussa doit encore 700 - 200 = 500 F ---
        mvc.perform(get("/api/reports/credits"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerCount").value(1))
                .andExpect(jsonPath("$.totalOutstanding").value(500))
                .andExpect(jsonPath("$.customers[0].name").value("Moussa"))
                .andExpect(jsonPath("$.customers[0].balance").value(500))
                .andExpect(jsonPath("$.customers[0].daysWaiting").value(0));
    }

    private JsonNode product(String name, String unit, long price) throws Exception {
        return json.readTree(postJson("/api/products", """
                {"name": "%s", "baseUnit": "%s",
                 "packagings": [{"name": "%s", "factor": 1,
                   "prices": [{"level": "DETAIL", "price": %d, "floorPrice": %d}]}]}
                """.formatted(name, unit, unit, price, price))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private void reception(JsonNode product, long quantity, long totalCost) throws Exception {
        postJson("/api/stock/receptions", """
                {"productId": "%s", "packagingId": "%s", "quantity": %d, "totalCost": %d}
                """.formatted(product.at("/product/id").asText(), product.at("/packagings/0/id").asText(),
                quantity, totalCost)).andExpect(status().isCreated());
    }

    /** Vente de bouteilles à 1 200 F, dont cashPaid F payés en espèces ; le reste part en crédit. */
    private ResultActions sale(String packagingId, String customerId, long quantity, long cashPaid)
            throws Exception {
        String customer = customerId == null ? "null" : "\"" + customerId + "\"";
        return postJson("/api/sales", """
                {"idempotencyKey": "%s", "customerId": %s,
                 "items": [{"packagingId": "%s", "quantity": %d, "unitPrice": 1200}],
                 "payments": [{"method": "ESPECES", "amount": %d}]}
                """.formatted(UUID.randomUUID(), customer, packagingId, quantity, cashPaid));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
