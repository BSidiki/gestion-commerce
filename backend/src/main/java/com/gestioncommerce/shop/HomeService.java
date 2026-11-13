package com.gestioncommerce.shop;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.common.BusinessException;

/**
 * Réglages de la page d'accueil, choisis par le propriétaire pour toute la boutique :
 * un message, une photo de couverture, et les raccourcis proposés.
 */
@Service
public class HomeService {

    /** Les raccourcis possibles, dans leur ordre d'origine. L'interface n'affiche que ceux permis au rôle. */
    public static final List<String> SHORTCUTS = List.of(
            "vente", "devis", "depense", "cloture", "produit", "achat", "inventaire", "clients", "rapports",
            "import");
    private static final List<String> DEFAULT_SHORTCUTS = List.of(
            "vente", "devis", "depense", "cloture", "produit", "achat");

    private static final List<String> IMAGE_PREFIXES = List.of(
            "data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,");
    private static final int MAX_COVER_CHARS = 900_000;
    private static final int MAX_MESSAGE = 140;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public HomeService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * @param welcomeMessage phrase affichée sous le bonjour, ou vide
     * @param cover          photo de couverture (image encodée « data:image/…;base64,… »), ou vide
     * @param shortcuts      raccourcis affichés, dans l'ordre voulu
     */
    public record HomeSettings(String welcomeMessage, String cover, List<String> shortcuts) {
    }

    public HomeSettings get() {
        String stored = setting("home.shortcuts");
        List<String> shortcuts = stored == null
                ? DEFAULT_SHORTCUTS
                : Arrays.stream(stored.split(",")).filter(SHORTCUTS::contains).toList();
        return new HomeSettings(orEmpty(setting("home.welcome")), orEmpty(setting("home.cover")), shortcuts);
    }

    @Transactional
    public HomeSettings update(HomeSettings request) {
        String message = request.welcomeMessage() == null ? "" : request.welcomeMessage().trim();
        if (message.length() > MAX_MESSAGE) {
            throw new BusinessException("Le message d'accueil est trop long : " + MAX_MESSAGE + " caractères au plus.");
        }
        String cover = request.cover() == null ? "" : request.cover().trim();
        if (!cover.isEmpty() && IMAGE_PREFIXES.stream().noneMatch(cover::startsWith)) {
            throw new BusinessException("La photo doit être une image PNG, JPEG ou WebP.");
        }
        if (cover.length() > MAX_COVER_CHARS) {
            throw new BusinessException("La photo est trop lourde : choisissez une image plus petite.");
        }
        Set<String> shortcuts = new LinkedHashSet<>(request.shortcuts() == null ? DEFAULT_SHORTCUTS : request.shortcuts());
        for (String shortcut : shortcuts) {
            if (!SHORTCUTS.contains(shortcut)) {
                throw new BusinessException("Raccourci inconnu : " + shortcut + ".");
            }
        }
        save("home.welcome", message);
        save("home.cover", cover);
        save("home.shortcuts", String.join(",", shortcuts));
        audit.log("ACCUEIL_MODIFIE", "Page d'accueil : message, photo et raccourcis");
        return get();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private String setting(String key) {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = ?")
                .param(key)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private void save(String key, String value) {
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .params(key, value)
                .update();
    }
}
