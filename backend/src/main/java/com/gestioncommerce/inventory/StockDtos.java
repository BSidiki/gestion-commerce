package com.gestioncommerce.inventory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class StockDtos {

    private StockDtos() {
    }

    public enum AdjustmentReason {
        PERTE,
        CASSE,
        VOL,
        ERREUR_SAISIE,
        INVENTAIRE
    }

    /**
     * Réception de marchandise.
     *
     * @param quantity  nombre de conditionnements reçus (ex. 2 cartons)
     * @param totalCost coût total de la réception, en francs CFA
     */
    public record ReceptionRequest(
            @NotBlank String productId,
            @NotBlank String packagingId,
            @Positive long quantity,
            @PositiveOrZero long totalCost,
            String note) {
    }

    /**
     * Correction de stock motivée.
     *
     * @param quantityDelta écart en unité de base, positif ou négatif
     */
    public record AdjustmentRequest(
            @NotBlank String productId,
            long quantityDelta,
            @NotNull AdjustmentReason reason,
            String note) {
    }

    public record MovementView(
            String id,
            String type,
            long quantity,
            Long unitCostMilli,
            String reason,
            String note,
            long stockAfter,
            String createdAt) {
    }

    public record StockLevel(String productId, long stockQty, long avgCostMilli) {
    }
}
