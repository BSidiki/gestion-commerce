package com.gestioncommerce;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Inventaire physique : compter, voir les écarts, valider ; une vente faite entre-temps reste comptée. */
@SpringBootTest
@AutoConfigureMockMvc
class StockCountIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-inventaire");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void compterPuisValider() throws Exception {
        JsonNode oil = product("Huile 1 L", "bouteille", 1200);
        String oilId = oil.at("/product/id").asText();
        reception(oil, 20, 18000); // 900 F la bouteille
        JsonNode salt = product("Sel fin", "sachet", 200);
        String saltId = salt.at("/product/id").asText();
        reception(salt, 10, 1000); // 100 F le sachet

        // --- Un seul inventaire à la fois ---
        String countId = json.readTree(postJson("/api/stock-counts", """
                {"label": "Rayon épicerie"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count.number").value(1))
                .andExpect(jsonPath("$.count.status").value("EN_COURS"))
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andReturn().getResponse().getContentAsString()).at("/count/id").asText();
        postJson("/api/stock-counts", "{}").andExpect(status().isUnprocessableEntity());

        // --- On compte 18 bouteilles là où le logiciel en attend 20 : il en manque 2, soit 1 800 F ---
        count(countId, oilId, 18)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].productName").value("Huile 1 L"))
                .andExpect(jsonPath("$.lines[0].expectedQty").value(20))
                .andExpect(jsonPath("$.lines[0].difference").value(-2))
                .andExpect(jsonPath("$.lines[0].valueDifference").value(-1800))
                .andExpect(jsonPath("$.count.missingValue").value(1800));

        // Trois bouteilles sont vendues après le comptage : le stock du logiciel passe à 17.
        postJson("/api/sales", """
                {"idempotencyKey": "%s", "customerId": null,
                 "items": [{"packagingId": "%s", "quantity": 3, "unitPrice": 1200}],
                 "payments": [{"method": "ESPECES", "amount": 3600}]}
                """.formatted(UUID.randomUUID(), oil.at("/packagings/0/id").asText()))
                .andExpect(status().isCreated());

        // --- Le sel : compté 12 par erreur, puis recompté à 10 ; le second comptage remplace le premier ---
        count(countId, saltId, 12).andExpect(jsonPath("$.count.surplusValue").value(200));
        count(countId, saltId, 10)
                .andExpect(jsonPath("$.count.lineCount").value(2))
                .andExpect(jsonPath("$.count.differenceCount").value(1))
                .andExpect(jsonPath("$.count.surplusValue").value(0));
        count(countId, saltId, -1).andExpect(status().isUnprocessableEntity());
        count(countId, "inconnu", 1).andExpect(status().isNotFound());

        // Une ligne se retire, puis se recompte.
        mvc.perform(delete("/api/stock-counts/" + countId + "/lines/" + saltId))
                .andExpect(jsonPath("$.count.lineCount").value(1));
        count(countId, saltId, 10).andExpect(jsonPath("$.count.lineCount").value(2));

        // --- Validation : l'écart de -2 s'applique au stock courant (17), pas au stock d'avant la vente ---
        postJson("/api/stock-counts/" + countId + "/validate", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count.status").value("VALIDE"));
        mvc.perform(get("/api/products/" + oilId)).andExpect(jsonPath("$.product.stockQty").value(15));
        mvc.perform(get("/api/products/" + saltId)).andExpect(jsonPath("$.product.stockQty").value(10));
        mvc.perform(get("/api/products/" + oilId + "/movements"))
                .andExpect(jsonPath("$[0].type").value("AJUSTEMENT"))
                .andExpect(jsonPath("$[0].quantity").value(-2))
                .andExpect(jsonPath("$[0].reason").value("INVENTAIRE"));
        // Le sel n'avait pas d'écart : aucun mouvement de plus que sa réception.
        mvc.perform(get("/api/products/" + saltId + "/movements")).andExpect(jsonPath("$.length()").value(1));

        // Un inventaire terminé ne bouge plus.
        count(countId, oilId, 1).andExpect(status().isUnprocessableEntity());
        postJson("/api/stock-counts/" + countId + "/validate", "").andExpect(status().isUnprocessableEntity());

        // --- Abandonner ne corrige rien ; valider sans rien avoir compté est refusé ---
        String second = json.readTree(postJson("/api/stock-counts", "{}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.count.number").value(2))
                .andReturn().getResponse().getContentAsString()).at("/count/id").asText();
        postJson("/api/stock-counts/" + second + "/validate", "").andExpect(status().isUnprocessableEntity());
        count(second, oilId, 0).andExpect(jsonPath("$.lines[0].difference").value(-15));
        postJson("/api/stock-counts/" + second + "/abandon", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count.status").value("ABANDONNE"));
        mvc.perform(get("/api/products/" + oilId)).andExpect(jsonPath("$.product.stockQty").value(15));

        mvc.perform(get("/api/stock-counts"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].number").value(2))
                .andExpect(jsonPath("$[1].label").value("Rayon épicerie"))
                .andExpect(jsonPath("$[1].differenceCount").value(1));
    }

    private ResultActions count(String countId, String productId, long quantity) throws Exception {
        return mvc.perform(put("/api/stock-counts/" + countId + "/lines/" + productId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"countedQty\": " + quantity + "}"));
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

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
