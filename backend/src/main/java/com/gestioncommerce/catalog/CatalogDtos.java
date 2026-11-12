package com.gestioncommerce.catalog;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Objets échangés avec l'interface pour le catalogue. Montants en francs CFA entiers. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CategoryRequest(@NotBlank String name) {
    }

    public record CategoryView(String id, String name) {
    }

    public record PriceRequest(
            @NotNull PriceLevel level,
            @PositiveOrZero long price,
            @PositiveOrZero long floorPrice) {
    }

    public record PackagingRequest(
            @NotBlank String name,
            @Min(1) long factor,
            String barcode,
            @Valid List<PriceRequest> prices) {
    }

    public record ProductRequest(
            @NotBlank String name,
            String categoryId,
            @NotBlank String baseUnit,
            @PositiveOrZero long alertThreshold,
            @NotEmpty @Valid List<PackagingRequest> packagings) {
    }

    public record PriceView(PriceLevel level, long price, long floorPrice) {
    }

    public record PackagingView(String id, String name, long factor, List<String> barcodes, List<PriceView> prices) {
    }

    public record ProductSummary(
            String id,
            String name,
            String categoryId,
            String baseUnit,
            long stockQty,
            long avgCostMilli,
            long alertThreshold,
            boolean lowStock) {
    }

    public record ProductDetail(ProductSummary product, List<PackagingView> packagings) {
    }
}
