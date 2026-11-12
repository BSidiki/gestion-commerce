package com.gestioncommerce;

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

import com.fasterxml.jackson.databind.ObjectMapper;

/** Une session de caisse : ouverture, ventes, dépenses, remboursement, clôture avec écart. */
@SpringBootTest
@AutoConfigureMockMvc
class CashIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-caisse");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void uneSessionDeCaisse() throws Exception {
        String packagingId = json.readTree(postJson("/api/products", """
                {"name": "Huile 1 L", "baseUnit": "bouteille",
                 "packagings": [{"name": "bouteille", "factor": 1,
                   "prices": [{"level": "DETAIL", "price": 1200, "floorPrice": 1100}]}]}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .at("/packagings/0/id").asText();
        String customerId = json.readTree(postJson("/api/customers", """
                {"name": "Moussa"}
                """).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .at("/id").asText();

        // Avant l'ouverture : pas de caisse, et on ne peut pas clôturer.
        mvc.perform(get("/api/cash/current")).andExpect(jsonPath("$.open").value(false));
        postJson("/api/cash/close", """
                {"countedCash": 0}
                """).andExpect(status().isUnprocessableEntity());

        // Une vente faite caisse fermée n'entrera dans aucune session.
        sale(packagingId, null, 1200).andExpect(status().isCreated());

        // --- Ouverture avec 10 000 F de fond de caisse ---
        postJson("/api/cash/open", """
                {"openingFloat": 10000}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OUVERTE"))
                .andExpect(jsonPath("$.expectedCash").value(10000));

        postJson("/api/cash/open", """
                {"openingFloat": 0}
                """).andExpect(status().isUnprocessableEntity());

        // Vente payée en espèces, vente à crédit (500 F payés), vente annulée.
        sale(packagingId, null, 1200).andExpect(status().isCreated());
        sale(packagingId, customerId, 500).andExpect(status().isCreated());
        String cancelledId = json.readTree(sale(packagingId, null, 1200)
                .andReturn().getResponse().getContentAsString()).at("/id").asText();
        postJson("/api/sales/" + cancelledId + "/cancel", """
                {"reason": "Erreur"}
                """).andExpect(status().isOk());

        // Le client rembourse 300 F en espèces et 200 F en Mobile Money.
        postJson("/api/customers/" + customerId + "/repayments", """
                {"amount": 300, "method": "ESPECES"}
                """).andExpect(status().isCreated());
        postJson("/api/customers/" + customerId + "/repayments", """
                {"amount": 200, "method": "MOBILE_MONEY"}
                """).andExpect(status().isCreated());

        // Dépenses : 500 F en espèces, 300 F en Mobile Money. Sans catégorie : refusée.
        postJson("/api/expenses", """
                {"category": "Transport", "amount": 500, "method": "ESPECES", "note": "Taxi marchandises"}
                """).andExpect(status().isCreated()).andExpect(jsonPath("$.category").value("Transport"));
        postJson("/api/expenses", """
                {"category": "Crédit téléphone", "amount": 300, "method": "MOBILE_MONEY"}
                """).andExpect(status().isCreated());
        postJson("/api/expenses", """
                {"category": "", "amount": 100, "method": "ESPECES"}
                """).andExpect(status().isBadRequest());

        mvc.perform(get("/api/expenses"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].category").value("Crédit téléphone"));

        // Espèces attendues : 10 000 + 1 200 + 500 + 300 (remboursement) - 500 (dépense) = 11 500 F.
        mvc.perform(get("/api/cash/current"))
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.session.cashIn").value(2000))
                .andExpect(jsonPath("$.session.cashExpenses").value(500))
                .andExpect(jsonPath("$.session.expectedCash").value(11500));

        // Rapport du jour : 3 ventes valides de 1 200 F (dont celle d'avant l'ouverture), 800 F de dépenses.
        // Aucun coût d'achat n'a été saisi : la marge brute est égale au chiffre d'affaires.
        mvc.perform(get("/api/reports/daily"))
                .andExpect(jsonPath("$.saleCount").value(3))
                .andExpect(jsonPath("$.revenue").value(3600))
                .andExpect(jsonPath("$.grossMargin").value(3600))
                .andExpect(jsonPath("$.expenses").value(800))
                .andExpect(jsonPath("$.estimatedResult").value(2800));

        // --- Clôture : 11 300 F comptés, il manque 200 F ---
        postJson("/api/cash/close", """
                {"countedCash": 11300, "note": "Billet manquant"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOTUREE"))
                .andExpect(jsonPath("$.expectedCash").value(11500))
                .andExpect(jsonPath("$.countedCash").value(11300))
                .andExpect(jsonPath("$.difference").value(-200))
                .andExpect(jsonPath("$.note").value("Billet manquant"));

        mvc.perform(get("/api/cash/current")).andExpect(jsonPath("$.open").value(false));

        // Une vente après la clôture ne modifie pas la session clôturée.
        sale(packagingId, null, 1200).andExpect(status().isCreated());
        mvc.perform(get("/api/cash/sessions"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].expectedCash").value(11500))
                .andExpect(jsonPath("$[0].cashIn").value(2000));
    }

    /** Vente d'une bouteille à 1 200 F, dont cashPaid F payés en espèces ; le reste part en crédit. */
    private ResultActions sale(String packagingId, String customerId, long cashPaid) throws Exception {
        String customer = customerId == null ? "null" : "\"" + customerId + "\"";
        return postJson("/api/sales", """
                {"idempotencyKey": "%s", "customerId": %s,
                 "items": [{"packagingId": "%s", "quantity": 1, "unitPrice": 1200}],
                 "payments": [{"method": "ESPECES", "amount": %d}]}
                """.formatted(UUID.randomUUID(), customer, packagingId, cashPaid));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
