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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gestioncommerce.auth.AuthController;

import jakarta.servlet.http.Cookie;

/** Remise sur le total d'une vente, et devis transformé en vente. */
@SpringBootTest
@AutoConfigureMockMvc
class QuoteIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-devis");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void remisesEtDevis() throws Exception {
        // Huile à 1 200 F, prix plancher 1 100 F, achetée 900 F.
        JsonNode oil = json.readTree(postJson(null, "/api/products", """
                {"name": "Huile 1 L", "baseUnit": "bouteille",
                 "packagings": [{"name": "bouteille", "factor": 1,
                   "prices": [{"level": "DETAIL", "price": 1200, "floorPrice": 1100}]}]}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String productId = oil.at("/product/id").asText();
        String packagingId = oil.at("/packagings/0/id").asText();
        postJson(null, "/api/stock/receptions", """
                {"productId": "%s", "packagingId": "%s", "quantity": 30, "totalCost": 27000}
                """.formatted(productId, packagingId)).andExpect(status().isCreated());

        // --- Remise sur le total : 5 bouteilles = 6 000 F, plancher 5 500 F ---
        sale(null, packagingId, 5, 400, 5600, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discount").value(400))
                .andExpect(jsonPath("$.total").value(5600))
                .andExpect(jsonPath("$.paid").value(5600));
        // 600 F de remise feraient passer la vente sous les prix planchers ; 7 000 F dépassent le total.
        sale(null, packagingId, 5, 600, 5400, null).andExpect(status().isUnprocessableEntity());
        sale(null, packagingId, 5, 7000, 0, null).andExpect(status().isUnprocessableEntity());
        // Le rapport du jour compte le chiffre d'affaires net de la remise : marge 5 600 - 4 500.
        mvc.perform(get("/api/reports/daily"))
                .andExpect(jsonPath("$.revenue").value(5600))
                .andExpect(jsonPath("$.grossMargin").value(1100));

        // --- Devis : 10 bouteilles à 1 150 F, remise de 500 F, soit 11 000 F ---
        String quoteBody = """
                {"customerName": "Restaurant Le Baobab", "validDays": 10, "discount": %d, "note": "Livraison comprise",
                 "items": [{"packagingId": "%s", "quantity": 10, "unitPrice": 1150}]}
                """;
        String quoteId = json.readTree(postJson(null, "/api/quotes", quoteBody.formatted(500, packagingId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.status").value("OUVERT"))
                .andExpect(jsonPath("$.customerName").value("Restaurant Le Baobab"))
                .andExpect(jsonPath("$.discount").value(500))
                .andExpect(jsonPath("$.total").value(11000))
                .andExpect(jsonPath("$.validUntil").value(LocalDate.now().plusDays(10).toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Huile 1 L"))
                .andExpect(jsonPath("$.items[0].packagingId").value(packagingId))
                .andExpect(jsonPath("$.items[0].lineTotal").value(11500))
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        // Un devis suit les mêmes prix planchers qu'une vente, et ne touche pas au stock.
        postJson(null, "/api/quotes", quoteBody.formatted(600, packagingId))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/products/" + productId)).andExpect(jsonPath("$.product.stockQty").value(25));

        // --- Le devis devient une vente, une seule fois ---
        saleFromQuote(packagingId, quoteId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(2))
                .andExpect(jsonPath("$.total").value(11000));
        mvc.perform(get("/api/quotes/" + quoteId))
                .andExpect(jsonPath("$.status").value("TRANSFORME"))
                .andExpect(jsonPath("$.saleNumber").value(2));
        mvc.perform(get("/api/products/" + productId)).andExpect(jsonPath("$.product.stockQty").value(15));
        // Une seconde vente sur le même devis est refusée en entier : le stock ne bouge pas.
        saleFromQuote(packagingId, quoteId).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/products/" + productId)).andExpect(jsonPath("$.product.stockQty").value(15));
        postJson(null, "/api/quotes/" + quoteId + "/cancel", "").andExpect(status().isUnprocessableEntity());

        // --- Un devis annulé ne se transforme plus ---
        String second = json.readTree(postJson(null, "/api/quotes", quoteBody.formatted(0, packagingId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(2))
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        postJson(null, "/api/quotes/" + second + "/cancel", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANNULE"));
        saleFromQuote(packagingId, second).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/quotes"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].number").value(2))
                .andExpect(jsonPath("$[1].status").value("TRANSFORME"));

        // --- Le propriétaire peut accorder une remise sous les planchers ; elle est inscrite au journal ---
        Cookie owner = postJson(null, "/api/auth/setup", """
                {"username": "awa", "displayName": "Awa", "password": "secret-awa"}
                """).andExpect(status().isCreated()).andReturn().getResponse().getCookie(AuthController.SESSION_COOKIE);
        sale(owner, packagingId, 5, 600, 5400, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(5400));
        mvc.perform(get("/api/audit").cookie(owner))
                .andExpect(jsonPath("$[?(@.action == 'VENTE_SOUS_PLANCHER')]").isNotEmpty());
    }

    /** Vente de bouteilles à 1 200 F avec une remise sur le total, payée en espèces. */
    private ResultActions sale(Cookie session, String packagingId, long quantity, long discount, long paid,
            String quoteId) throws Exception {
        String payments = paid > 0 ? "[{\"method\": \"ESPECES\", \"amount\": " + paid + "}]" : "[]";
        return postJson(session, "/api/sales", """
                {"idempotencyKey": "%s", "discount": %d, "quoteId": %s,
                 "items": [{"packagingId": "%s", "quantity": %d, "unitPrice": 1200}],
                 "payments": %s}
                """.formatted(UUID.randomUUID(), discount, quoteId == null ? "null" : "\"" + quoteId + "\"",
                packagingId, quantity, payments));
    }

    /** La vente qui reprend le devis : 10 bouteilles à 1 150 F, remise de 500 F, payée 11 000 F. */
    private ResultActions saleFromQuote(String packagingId, String quoteId) throws Exception {
        return postJson(null, "/api/sales", """
                {"idempotencyKey": "%s", "discount": 500, "quoteId": "%s",
                 "items": [{"packagingId": "%s", "quantity": 10, "unitPrice": 1150}],
                 "payments": [{"method": "ESPECES", "amount": 11000}]}
                """.formatted(UUID.randomUUID(), quoteId, packagingId));
    }

    private ResultActions postJson(Cookie session, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(session == null ? request : request.cookie(session));
    }
}
