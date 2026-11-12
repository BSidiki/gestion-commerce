package com.gestioncommerce.sales;

import java.util.List;

import com.gestioncommerce.common.PaymentMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class SalesDtos {

    private SalesDtos() {
    }

    /**
     * @param quantity  nombre de conditionnements vendus (ex. 1 carton, 3 unités)
     * @param unitPrice prix réellement appliqué pour un conditionnement, après négociation
     */
    public record SaleItemRequest(
            @NotBlank String packagingId,
            @Positive long quantity,
            @PositiveOrZero long unitPrice) {
    }

    public record PaymentRequest(@NotNull PaymentMethod method, @Positive long amount) {
    }

    /**
     * @param idempotencyKey identifiant unique généré par l'interface pour cette validation :
     *                       renvoyer la même requête ne crée pas une seconde vente
     * @param customerId     obligatoire si la vente n'est pas entièrement payée
     * @param payments       encaissements ; ce qui manque pour atteindre le total part en crédit
     */
    public record SaleRequest(
            @NotBlank String idempotencyKey,
            String customerId,
            @NotEmpty @Valid List<SaleItemRequest> items,
            @Valid List<PaymentRequest> payments) {
    }

    public record CancelRequest(@NotBlank String reason) {
    }

    public record SaleItemView(
            String productId,
            String productName,
            String packagingName,
            long factor,
            long quantity,
            long unitPrice,
            Long listPrice,
            long lineTotal) {
    }

    public record PaymentView(PaymentMethod method, long amount) {
    }

    public record SaleView(
            String id,
            long number,
            String customerId,
            long total,
            long paid,
            long credit,
            String status,
            String businessDate,
            String createdAt,
            String cancelReason,
            List<SaleItemView> items,
            List<PaymentView> payments) {
    }

    /**
     * Résumé d'une journée. Les ventes annulées sont exclues.
     *
     * @param revenue            chiffre d'affaires : total des ventes, crédits compris
     * @param costOfGoods        coût des marchandises vendues
     * @param grossMargin        revenue - costOfGoods
     * @param cash               espèces encaissées (ventes et remboursements de crédits)
     * @param mobileMoney        Mobile Money encaissé (ventes et remboursements de crédits)
     * @param creditGranted      part des ventes du jour laissée à crédit
     * @param repaymentsReceived remboursements de crédits reçus dans la journée
     */
    public record DailyReport(
            String date,
            long saleCount,
            long revenue,
            long costOfGoods,
            long grossMargin,
            long cash,
            long mobileMoney,
            long creditGranted,
            long repaymentsReceived) {
    }
}
