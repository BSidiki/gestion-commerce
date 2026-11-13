package com.gestioncommerce.shop;

import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.common.BusinessException;

/**
 * Coordonnées de la boutique, imprimées en tête du reçu, format du papier, et apparence
 * de l'application (couleur, logo). Tout est facultatif : sans réglage, le reçu sort sans
 * en-tête, au format 80 mm, et l'application garde sa couleur d'origine.
 */
@Service
public class ShopService {

    /** Largeur du papier : rouleau de 58 mm, rouleau de 80 mm, ou feuille A4. */
    public static final Set<String> WIDTHS = Set.of("58", "80", "A4");
    public static final String DEFAULT_WIDTH = "80";

    /** Couleurs proposées pour l'application. Les teintes elles-mêmes sont définies dans l'interface. */
    public static final Set<String> ACCENTS = Set.of(
            "lagune", "ocean", "indigo", "violette", "bissap", "braise", "karite", "foret", "ardoise");
    public static final String DEFAULT_ACCENT = "lagune";

    /** Le logo est enregistré tel que l'interface l'envoie : une image réduite, encodée en texte. */
    private static final List<String> LOGO_PREFIXES = List.of(
            "data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,");
    private static final int MAX_LOGO_CHARS = 400_000;

    private static final int MAX_LINE = 80;
    private static final int MAX_FOOTER = 200;

    private final JdbcClient jdbc;
    private final AuditService audit;

    public ShopService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /**
     * @param name          nom de la boutique, en tête du reçu
     * @param address       adresse ou repère (quartier, marché, ville)
     * @param phone         téléphone affiché sur le reçu
     * @param receiptFooter phrase de fin de reçu (« Merci de votre visite »)
     * @param receiptWidth  « 58 », « 80 » ou « A4 »
     * @param accentColor   couleur de l'application, parmi {@link #ACCENTS}
     * @param logo          logo de la boutique (image encodée « data:image/…;base64,… »), ou vide
     */
    public record ShopSettings(String name, String address, String phone, String receiptFooter,
            String receiptWidth, String accentColor, String logo) {
    }

    /** Ce que l'écran de connexion a le droit de connaître avant toute session. */
    public record Branding(String name, String accentColor, String logo) {
    }

    public Branding branding() {
        ShopSettings shop = get();
        return new Branding(shop.name(), shop.accentColor(), shop.logo());
    }

    public ShopSettings get() {
        return new ShopSettings(
                setting("shop.name"),
                setting("shop.address"),
                setting("shop.phone"),
                setting("shop.receiptFooter"),
                WIDTHS.contains(setting("shop.receiptWidth")) ? setting("shop.receiptWidth") : DEFAULT_WIDTH,
                ACCENTS.contains(setting("shop.accentColor")) ? setting("shop.accentColor") : DEFAULT_ACCENT,
                setting("shop.logo"));
    }

    @Transactional
    public ShopSettings update(ShopSettings request) {
        String width = clean(request.receiptWidth(), "Le format du papier", 5);
        if (width.isEmpty()) {
            width = DEFAULT_WIDTH;
        }
        if (!WIDTHS.contains(width)) {
            throw new BusinessException("Format de papier inconnu : choisissez 58 mm, 80 mm ou A4.");
        }
        String accent = clean(request.accentColor(), "La couleur", 20);
        if (accent.isEmpty()) {
            accent = DEFAULT_ACCENT;
        }
        if (!ACCENTS.contains(accent)) {
            throw new BusinessException("Couleur inconnue : choisissez-en une dans la liste proposée.");
        }
        String logo = request.logo() == null ? "" : request.logo().trim();
        if (!logo.isEmpty() && LOGO_PREFIXES.stream().noneMatch(logo::startsWith)) {
            throw new BusinessException("Le logo doit être une image PNG, JPEG ou WebP.");
        }
        if (logo.length() > MAX_LOGO_CHARS) {
            throw new BusinessException("Le logo est trop lourd : choisissez une image plus petite.");
        }
        save("shop.name", clean(request.name(), "Le nom de la boutique", MAX_LINE));
        save("shop.address", clean(request.address(), "L'adresse", MAX_LINE));
        save("shop.phone", clean(request.phone(), "Le téléphone", MAX_LINE));
        save("shop.receiptFooter", clean(request.receiptFooter(), "La phrase de fin de reçu", MAX_FOOTER));
        save("shop.receiptWidth", width);
        save("shop.accentColor", accent);
        save("shop.logo", logo);
        audit.log("BOUTIQUE_MODIFIEE", "Coordonnées, apparence et format du reçu");
        return get();
    }

    private static String clean(String text, String label, int max) {
        String value = text == null ? "" : text.trim();
        if (value.length() > max) {
            throw new BusinessException(label + " est trop long : " + max + " caractères au plus.");
        }
        return value;
    }

    private String setting(String key) {
        return jdbc.sql("SELECT value FROM app_setting WHERE key = ?")
                .param(key)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private void save(String key, String value) {
        jdbc.sql("INSERT INTO app_setting (key, value) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET value = excluded.value")
                .params(key, value)
                .update();
    }
}
