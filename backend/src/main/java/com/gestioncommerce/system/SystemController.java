package com.gestioncommerce.system;

import java.util.Map;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.gestioncommerce.Application;

@Controller
public class SystemController {

    /** Sert au lanceur pour reconnaître une instance déjà démarrée. */
    @GetMapping("/api/health")
    @ResponseBody
    Map<String, String> health() {
        return Map.of("app", Application.APP_NAME, "status", "ok");
    }

    /**
     * Les pages de l'interface sont gérées par Angular dans le navigateur. Quand on
     * recharge une page ou qu'on ouvre un lien direct, le serveur renvoie l'interface,
     * qui affiche ensuite la bonne page. Toute nouvelle page de l'interface doit être ajoutée ici.
     */
    @GetMapping({
            "/ventes",
            "/clients",
            "/clients/{id}",
            "/depenses",
            "/cloture",
            "/produits",
            "/produits/{id}",
            "/produits/{id}/modifier",
            "/catalogue/importer",
            "/boutique",
            "/tableau-de-bord",
            "/sauvegarde",
            "/licence",
            "/connexion",
            "/installation",
            "/utilisateurs",
            "/compte",
            "/journal"
    })
    String interfacePage() {
        return "forward:/index.html";
    }
}
