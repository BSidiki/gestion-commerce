package com.gestioncommerce.shop;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.shop.ShopService.Branding;
import com.gestioncommerce.shop.ShopService.ShopSettings;

@RestController
public class ShopController {

    private final ShopService shop;

    public ShopController(ShopService shop) {
        this.shop = shop;
    }

    /** Lu par tous les comptes : la caisse en a besoin pour imprimer le reçu. */
    @GetMapping("/api/shop")
    ShopSettings get() {
        return shop.get();
    }

    /** Nom, couleur et logo : lus sans session, pour habiller l'écran de connexion. */
    @GetMapping("/api/branding")
    Branding branding() {
        return shop.branding();
    }

    /** Réservé au propriétaire (voir AuthInterceptor). */
    @PutMapping("/api/shop")
    ShopSettings update(@RequestBody ShopSettings request) {
        return shop.update(request);
    }
}
