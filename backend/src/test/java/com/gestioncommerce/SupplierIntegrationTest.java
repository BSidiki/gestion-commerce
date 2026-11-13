package com.gestioncommerce;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Fournisseurs : un achat entre en stock et crée une dette ; les paiements la réduisent. */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-fournisseurs");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void achatsEtDettesFournisseurs() throws Exception {
        // --- Un fournisseur ; son nom est unique, sans tenir compte des majuscules ---
        String supplierId = json.readTree(postJson("/api/suppliers", """
                {"name": "Grossiste Koné", "phone": "70 55 66 77"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0))
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        postJson("/api/suppliers", """
                {"name": "grossiste koné"}
                """).andExpect(status().isUnprocessableEntity());
        mvc.perform(put("/api/suppliers/" + supplierId).contentType(MediaType.APPLICATION_JSON).content("""
                {"name": "Grossiste Koné", "phone": "70 55 66 77", "note": "Livre le mardi"}
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Livre le mardi"));

        JsonNode product = json.readTree(postJson("/api/products", """
                {"name": "Huile 1 L", "baseUnit": "bouteille",
                 "packagings": [
                   {"name": "bouteille", "factor": 1,
                    "prices": [{"level": "DETAIL", "price": 1200, "floorPrice": 1100}]},
                   {"name": "Carton", "factor": 12,
                    "prices": [{"level": "GROS", "price": 13000, "floorPrice": 12500}]}]}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String productId = product.at("/product/id").asText();
        String bottle = product.at("/packagings/0/id").asText();
        String carton = product.at("/packagings/1/id").asText();

        postJson("/api/cash/open", """
                {"openingFloat": 10000}
                """).andExpect(status().isCreated());

        // --- Achat : 2 cartons à 21 600 F et 5 bouteilles à 5 000 F ; 10 000 F payés avec la caisse ---
        String purchase = """
                {"supplierId": "%s", "note": "Livraison du mardi",
                 "items": [{"packagingId": "%s", "quantity": 2, "totalCost": 21600},
                           {"packagingId": "%s", "quantity": 5, "totalCost": 5000}],
                 "paidAmount": %d, "paidMethod": "ESPECES", "fromCashDrawer": true}
                """;
        String purchaseId = json.readTree(postJson("/api/purchases",
                purchase.formatted(supplierId, carton, bottle, 10000))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.supplierName").value("Grossiste Koné"))
                .andExpect(jsonPath("$.total").value(26600))
                .andExpect(jsonPath("$.paid").value(10000))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].packagingName").value("Carton"))
                .andReturn().getResponse().getContentAsString()).at("/id").asText();

        // La marchandise est en stock : 24 + 5 bouteilles, au coût moyen de 26 600 / 29 F.
        mvc.perform(get("/api/products/" + productId))
                .andExpect(jsonPath("$.product.stockQty").value(29))
                .andExpect(jsonPath("$.product.avgCostMilli").value(917_241));
        // Le reste est dû au fournisseur, et les espèces sorties du tiroir comptent à la clôture.
        mvc.perform(get("/api/suppliers"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].balance").value(16600));
        mvc.perform(get("/api/cash/current"))
                .andExpect(jsonPath("$.session.supplierCash").value(10000))
                .andExpect(jsonPath("$.session.expectedCash").value(0));
        mvc.perform(get("/api/purchases/" + purchaseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.note").value("Livraison du mardi"))
                .andExpect(jsonPath("$.items[1].quantity").value(5));

        // --- Un achat refusé ne laisse aucune trace : ni stock, ni dette ---
        postJson("/api/purchases", purchase.formatted(supplierId, carton, bottle, 30000))
                .andExpect(status().isUnprocessableEntity()); // payé plus que le total
        postJson("/api/purchases", purchase.formatted(supplierId, carton, "inconnu", 0))
                .andExpect(status().isUnprocessableEntity()); // conditionnement inconnu en deuxième ligne
        mvc.perform(get("/api/products/" + productId)).andExpect(jsonPath("$.product.stockQty").value(29));
        mvc.perform(get("/api/suppliers")).andExpect(jsonPath("$[0].balance").value(16600));

        // --- Paiements : 6 600 F en Mobile Money ; jamais plus que la dette ---
        postJson("/api/suppliers/" + supplierId + "/payments", """
                {"amount": 6600, "method": "MOBILE_MONEY", "note": "Orange Money"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(10000));
        postJson("/api/suppliers/" + supplierId + "/payments", """
                {"amount": 20000, "method": "ESPECES"}
                """).andExpect(status().isUnprocessableEntity());
        postJson("/api/suppliers/" + supplierId + "/payments", """
                {"amount": 1000, "method": "MOBILE_MONEY", "fromCashDrawer": true}
                """).andExpect(status().isUnprocessableEntity()); // seules des espèces sortent de la caisse

        // --- Historique, le plus récent en premier ---
        mvc.perform(get("/api/suppliers/" + supplierId))
                .andExpect(jsonPath("$.supplier.balance").value(10000))
                .andExpect(jsonPath("$.entries.length()").value(3))
                .andExpect(jsonPath("$.entries[0].type").value("PAIEMENT"))
                .andExpect(jsonPath("$.entries[0].amount").value(6600))
                .andExpect(jsonPath("$.entries[0].fromCashDrawer").value(false))
                .andExpect(jsonPath("$.entries[1].type").value("PAIEMENT"))
                .andExpect(jsonPath("$.entries[1].purchaseNumber").value(1))
                .andExpect(jsonPath("$.entries[1].fromCashDrawer").value(true))
                .andExpect(jsonPath("$.entries[2].type").value("ACHAT"))
                .andExpect(jsonPath("$.entries[2].amount").value(26600));

        // Caisse fermée : on ne peut plus payer « avec la caisse ».
        postJson("/api/cash/close", """
                {"countedCash": 0}
                """).andExpect(status().isOk()).andExpect(jsonPath("$.difference").value(0));
        postJson("/api/suppliers/" + supplierId + "/payments", """
                {"amount": 1000, "method": "ESPECES", "fromCashDrawer": true}
                """).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/suppliers/inconnu")).andExpect(status().isNotFound());

        // --- Annulation d'un achat : impossible dès qu'une partie de la marchandise est vendue ---
        postJson("/api/sales", """
                {"idempotencyKey": "vente-avant-annulation",
                 "items": [{"packagingId": "%s", "quantity": 10, "unitPrice": 1200}],
                 "payments": [{"method": "ESPECES", "amount": 12000}]}
                """.formatted(bottle)).andExpect(status().isCreated());
        String cancel = """
                {"reason": "Livraison refusée"}
                """;
        postJson("/api/purchases/" + purchaseId + "/cancel", cancel).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/products/" + productId)).andExpect(jsonPath("$.product.stockQty").value(19));
        mvc.perform(get("/api/suppliers")).andExpect(jsonPath("$[0].balance").value(10000));

        // Une fois le stock revenu à 29, l'achat s'annule : la marchandise ressort, la dette disparaît.
        postJson("/api/stock/receptions", """
                {"productId": "%s", "packagingId": "%s", "quantity": 10, "totalCost": 9172}
                """.formatted(productId, bottle)).andExpect(status().isCreated());
        postJson("/api/purchases/" + purchaseId + "/cancel", cancel)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ANNULE"))
                .andExpect(jsonPath("$.cancelReason").value("Livraison refusée"));
        mvc.perform(get("/api/products/" + productId)).andExpect(jsonPath("$.product.stockQty").value(0));
        mvc.perform(get("/api/products/" + productId + "/movements"))
                .andExpect(jsonPath("$[0].type").value("ANNULATION"))
                .andExpect(jsonPath("$[0].quantity").value(-5))
                .andExpect(jsonPath("$[1].quantity").value(-24));
        // Les 16 600 F déjà payés restent enregistrés : c'est maintenant le fournisseur qui les doit.
        mvc.perform(get("/api/suppliers/" + supplierId))
                .andExpect(jsonPath("$.supplier.balance").value(-16600))
                .andExpect(jsonPath("$.entries[2].type").value("ACHAT"))
                .andExpect(jsonPath("$.entries[2].cancelled").value(true))
                .andExpect(jsonPath("$.entries[0].cancelled").value(false));
        postJson("/api/purchases/" + purchaseId + "/cancel", cancel).andExpect(status().isUnprocessableEntity());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
