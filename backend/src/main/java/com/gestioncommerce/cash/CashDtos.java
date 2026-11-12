package com.gestioncommerce.cash;

import com.gestioncommerce.common.PaymentMethod;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class CashDtos {

    private CashDtos() {
    }

    /** @param openingFloat fond de caisse : les espèces présentes dans le tiroir à l'ouverture */
    public record OpenRequest(@PositiveOrZero long openingFloat) {
    }

    /** @param countedCash espèces réellement comptées dans le tiroir à la clôture */
    public record CloseRequest(@PositiveOrZero long countedCash, String note) {
    }

    /**
     * @param cashIn       espèces encaissées pendant la session (ventes non annulées et remboursements)
     * @param cashExpenses dépenses payées en espèces pendant la session
     * @param expectedCash openingFloat + cashIn - cashExpenses
     * @param countedCash  renseigné à la clôture
     * @param difference   countedCash - expectedCash : négatif s'il manque de l'argent
     */
    public record CashSessionView(
            String id,
            String status,
            long openingFloat,
            String openedAt,
            String closedAt,
            long cashIn,
            long cashExpenses,
            long expectedCash,
            Long countedCash,
            Long difference,
            String note) {
    }

    /** @param session la caisse ouverte, ou null s'il n'y en a pas */
    public record CashStatus(boolean open, CashSessionView session) {
    }

    public record ExpenseRequest(
            @NotBlank String category,
            @Positive long amount,
            @NotNull PaymentMethod method,
            String note) {
    }

    public record ExpenseView(
            String id,
            String category,
            long amount,
            PaymentMethod method,
            String note,
            String createdAt) {
    }
}
