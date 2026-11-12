package com.gestioncommerce.catalog;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.catalog.CatalogDtos.CategoryRequest;
import com.gestioncommerce.catalog.CatalogDtos.CategoryView;
import com.gestioncommerce.catalog.CatalogDtos.ProductDetail;
import com.gestioncommerce.catalog.CatalogDtos.ProductRequest;
import com.gestioncommerce.catalog.CatalogDtos.ProductSummary;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalog;

    public CatalogController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    CategoryView createCategory(@Valid @RequestBody CategoryRequest request) {
        return catalog.createCategory(request);
    }

    @GetMapping("/categories")
    List<CategoryView> listCategories() {
        return catalog.listCategories();
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    ProductDetail createProduct(@Valid @RequestBody ProductRequest request) {
        return catalog.createProduct(request);
    }

    /** lowStock=true : uniquement les produits au niveau ou sous leur seuil d'alerte. */
    @GetMapping("/products")
    List<ProductSummary> listProducts(@RequestParam(defaultValue = "false") boolean lowStock) {
        return catalog.listProducts(lowStock);
    }

    /** Catalogue complet avec conditionnements, prix et codes-barres, pour l'écran de caisse. */
    @GetMapping("/catalog/sellable")
    List<ProductDetail> listSellable() {
        return catalog.listSellable();
    }

    @GetMapping("/products/{id}")
    ProductDetail getProduct(@PathVariable String id) {
        return catalog.getProduct(id);
    }
}
