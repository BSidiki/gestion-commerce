package com.gestioncommerce;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
import com.gestioncommerce.auth.AuthController;

import jakarta.servlet.http.Cookie;

/** Coordonnées de la boutique et format du reçu : lus par tous, modifiés par le propriétaire seul. */
@SpringBootTest
@AutoConfigureMockMvc
class ShopIntegrationTest {

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) throws IOException {
        Path dir = Files.createTempDirectory("gestion-test-boutique");
        registry.add("app.data-dir", () -> dir.toString().replace('\\', '/'));
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @Test
    void coordonneesEtFormatDuRecu() throws Exception {
        // --- Sans réglage : pas d'en-tête, rouleau de 80 mm ---
        mvc.perform(get("/api/shop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(""))
                .andExpect(jsonPath("$.receiptWidth").value("80"))
                .andExpect(jsonPath("$.accentColor").value("lagune"))
                .andExpect(jsonPath("$.logo").value(""));

        Cookie owner = session(send(post("/api/auth/setup"), null,
                Map.of("username", "awa", "displayName", "Awa", "password", "secret-awa"))
                .andExpect(status().isCreated()));
        send(post("/api/users"), owner,
                Map.of("username", "moussa", "displayName", "Moussa", "role", "VENDEUR", "password", "secret-moussa"))
                .andExpect(status().isCreated());
        Cookie seller = session(send(post("/api/auth/login"), null,
                Map.of("username", "moussa", "password", "secret-moussa"))
                .andExpect(status().isOk()));

        // --- Le propriétaire renseigne sa boutique ---
        Map<String, String> settings = Map.of(
                "name", "  Boutique Wend-Kuuni  ", "address", "Marché de Sankaryaré, Ouagadougou",
                "phone", "70 00 00 00", "receiptFooter", "Merci de votre visite !", "receiptWidth", "58");
        send(put("/api/shop"), owner, settings)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Boutique Wend-Kuuni"))
                .andExpect(jsonPath("$.receiptWidth").value("58"));

        // --- Le vendeur les lit pour imprimer le reçu, mais ne les modifie pas ---
        mvc.perform(get("/api/shop").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Boutique Wend-Kuuni"))
                .andExpect(jsonPath("$.address").value("Marché de Sankaryaré, Ouagadougou"))
                .andExpect(jsonPath("$.phone").value("70 00 00 00"))
                .andExpect(jsonPath("$.receiptFooter").value("Merci de votre visite !"));
        send(put("/api/shop"), seller, settings).andExpect(status().isForbidden());

        // --- Format inconnu ou texte trop long : refusés, rien ne change ---
        send(put("/api/shop"), owner, Map.of("name", "X", "receiptWidth", "110"))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/shop"), owner, Map.of("name", "X".repeat(81), "receiptWidth", "80"))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/shop").cookie(owner))
                .andExpect(jsonPath("$.name").value("Boutique Wend-Kuuni"))
                .andExpect(jsonPath("$.receiptWidth").value("58"));

        // --- Apparence : une couleur de la liste, un logo en image ; rien d'autre ---
        String logo = "data:image/png;base64,iVBORw0KGgo=";
        Map<String, String> appearance = Map.of("name", "Boutique Wend-Kuuni", "receiptWidth", "58",
                "accentColor", "bissap", "logo", logo);
        send(put("/api/shop"), owner, appearance)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accentColor").value("bissap"))
                .andExpect(jsonPath("$.logo").value(logo));
        send(put("/api/shop"), owner, Map.of("name", "X", "accentColor", "#ff0000"))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/shop"), owner, Map.of("name", "X", "logo", "data:image/svg+xml;base64,PHN2Zz4="))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/shop"), owner, Map.of("name", "X", "logo", logo + "A".repeat(400_000)))
                .andExpect(status().isUnprocessableEntity());

        // L'écran de connexion lit le nom, la couleur et le logo sans session, et rien de plus.
        mvc.perform(get("/api/branding"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Boutique Wend-Kuuni"))
                .andExpect(jsonPath("$.accentColor").value("bissap"))
                .andExpect(jsonPath("$.logo").value(logo))
                .andExpect(jsonPath("$.phone").doesNotExist());
        mvc.perform(get("/api/shop")).andExpect(status().isUnauthorized());

        // --- Page d'accueil : message, photo et raccourcis choisis par le propriétaire ---
        mvc.perform(get("/api/home").cookie(seller))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.welcomeMessage").value(""))
                .andExpect(jsonPath("$.cover").value(""))
                .andExpect(jsonPath("$.shortcuts[0]").value("vente"))
                .andExpect(jsonPath("$.shortcuts.length()").value(6));
        Map<String, Object> home = Map.of("welcomeMessage", "  Bonne journée à toute l'équipe  ", "cover", logo,
                "shortcuts", java.util.List.of("cloture", "vente", "cloture"));
        send(put("/api/home"), seller, home).andExpect(status().isForbidden());
        send(put("/api/home"), owner, home)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.welcomeMessage").value("Bonne journée à toute l'équipe"))
                .andExpect(jsonPath("$.cover").value(logo))
                .andExpect(jsonPath("$.shortcuts.length()").value(2))
                .andExpect(jsonPath("$.shortcuts[0]").value("cloture"));
        send(put("/api/home"), owner, Map.of("shortcuts", java.util.List.of("vente", "pirater")))
                .andExpect(status().isUnprocessableEntity());
        send(put("/api/home"), owner, Map.of("welcomeMessage", "x".repeat(141)))
                .andExpect(status().isUnprocessableEntity());
        // Aucun raccourci est un choix permis : la liste vide est gardée telle quelle.
        send(put("/api/home"), owner, Map.of("shortcuts", java.util.List.of()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortcuts.length()").value(0))
                .andExpect(jsonPath("$.cover").value(""));

        // --- Le changement est inscrit au journal ---
        mvc.perform(get("/api/audit").cookie(owner))
                .andExpect(jsonPath("$[?(@.action == 'BOUTIQUE_MODIFIEE')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.action == 'ACCUEIL_MODIFIE')]").isNotEmpty());
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            Cookie session, Map<String, ?> body) throws Exception {
        request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        return mvc.perform(session == null ? request : request.cookie(session));
    }

    private static Cookie session(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getCookie(AuthController.SESSION_COOKIE);
    }
}
