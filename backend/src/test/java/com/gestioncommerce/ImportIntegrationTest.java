package com.gestioncommerce;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Import d'un tableau de produits : vérification sans effet, puis import ligne par ligne. */
@SpringBootTest
@AutoConfigureMockMvc
class ImportIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-import");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    /** Cinq lignes : deux bonnes, un doublon dans le tableau, un prix plancher trop haut, un nom vide. */
    private static final String ROWS = """
            [
              {"name": "Savon de Marseille", "category": "Hygiène", "baseUnit": "pièce",
               "retailPrice": 300, "retailFloor": 250, "barcode": "6001234567890", "alertThreshold": 50,
               "initialStock": 48, "unitCost": 200,
               "packagingName": "Carton", "packagingFactor": 24, "packagingPrice": 6500, "packagingFloor": 6000},
              {"name": "Riz parfumé", "category": "hygiène", "baseUnit": "kg", "retailPrice": 600},
              {"name": "savon de marseille", "retailPrice": 300},
              {"name": "Sucre", "retailPrice": 500, "retailFloor": 600},
              {"name": "  ", "retailPrice": 100}
            ]
            """;

    @Test
    void verifierPuisImporter() throws Exception {
        // --- Vérification : le même verdict que l'import, sans rien enregistrer ---
        importRows(true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.errors").value(3))
                .andExpect(jsonPath("$.rows[0].status").value("CREE"))
                .andExpect(jsonPath("$.rows[2].status").value("ERREUR"))
                .andExpect(jsonPath("$.rows[3].status").value("ERREUR"))
                .andExpect(jsonPath("$.rows[4].line").value(5))
                .andExpect(jsonPath("$.rows[4].status").value("ERREUR"));
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/categories")).andExpect(jsonPath("$.length()").value(0));

        // --- Import : les deux bonnes lignes sont créées, les trois autres signalées ---
        importRows(false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.errors").value(3));

        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));
        // Une seule catégorie : « Hygiène » et « hygiène » sont la même.
        mvc.perform(get("/api/categories"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Hygiène"));

        // Le savon : prix, conditionnement de gros, code-barres, stock initial valorisé.
        mvc.perform(get("/api/catalog/sellable"))
                .andExpect(jsonPath("$[1].product.name").value("Savon de Marseille"))
                .andExpect(jsonPath("$[1].product.stockQty").value(48))
                .andExpect(jsonPath("$[1].product.avgCostMilli").value(200_000))
                .andExpect(jsonPath("$[1].product.alertThreshold").value(50))
                .andExpect(jsonPath("$[1].packagings[0].name").value("pièce"))
                .andExpect(jsonPath("$[1].packagings[0].barcodes[0]").value("6001234567890"))
                .andExpect(jsonPath("$[1].packagings[0].prices[0].floorPrice").value(250))
                .andExpect(jsonPath("$[1].packagings[1].name").value("Carton"))
                .andExpect(jsonPath("$[1].packagings[1].factor").value(24))
                .andExpect(jsonPath("$[1].packagings[1].prices[0].level").value("GROS"))
                // Le riz : sans plancher donné, le plancher est le prix de vente ; pas de stock.
                .andExpect(jsonPath("$[0].product.name").value("Riz parfumé"))
                .andExpect(jsonPath("$[0].product.baseUnit").value("kg"))
                .andExpect(jsonPath("$[0].product.stockQty").value(0))
                .andExpect(jsonPath("$[0].packagings[0].prices[0].floorPrice").value(600));

        // --- Relancer le même import ne crée pas de doublon ---
        importRows(false)
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.rows[0].status").value("EXISTE"));
        mvc.perform(get("/api/products")).andExpect(jsonPath("$.length()").value(2));

        // --- Un tableau vide est refusé ---
        mvc.perform(post("/api/products/import").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\": false, \"rows\": []}"))
                .andExpect(status().isUnprocessableEntity());
    }

    private ResultActions importRows(boolean dryRun) throws Exception {
        return mvc.perform(post("/api/products/import").contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\": " + dryRun + ", \"rows\": " + ROWS + "}"));
    }
}
