package com.gestioncommerce.shop;

import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gestioncommerce.audit.AuditService;
import com.gestioncommerce.common.BusinessException;

/**
 * Coordonnées de la boutique, imprimées en tête du reçu, et format du papier.
 * Tout est facultatif : sans réglage, le reçu sort sans en-tête, au format 80 mm.
 */
@Service
public class ShopService {

    /** Largeur du papier : rouleau de 58 mm, rouleau de 80 mm, ou feuille A4. */
    public static final Set<String> WIDTHS = Set.of("58", "80", "A4");
    public static final String DEFAULT_WIDTH = "80";

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
     */
    public record ShopSettings(String name, String address, String phone, String receiptFooter,
            String receiptWidth) {
    }

    public ShopSettings get() {
        return new ShopSettings(
                setting("shop.name"),
                setting("shop.address"),
                setting("shop.phone"),
                setting("shop.receiptFooter"),
                WIDTHS.contains(setting("shop.receiptWidth")) ? setting("shop.receiptWidth") : DEFAULT_WIDTH);
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
        save("shop.name", clean(request.name(), "Le nom de la boutique", MAX_LINE));
        save("shop.address", clean(request.address(), "L'adresse", MAX_LINE));
        save("shop.phone", clean(request.phone(), "Le téléphone", MAX_LINE));
        save("shop.receiptFooter", clean(request.receiptFooter(), "La phrase de fin de reçu", MAX_FOOTER));
        save("shop.receiptWidth", width);
        audit.log("BOUTIQUE_MODIFIEE", "Coordonnées de la boutique et format du reçu");
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
