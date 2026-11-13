package com.gestioncommerce.suppliers;

import java.util.List;

import com.gestioncommerce.common.PaymentMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public final class SupplierDtos {

    private SupplierDtos() {
    }

    public record SupplierRequest(@NotBlank String name, String phone, String note) {
    }

    /** @param balance ce que la boutique doit encore à ce fournisseur, en francs CFA */
    public record SupplierView(String id, String name, String phone, String note, long balance) {
    }

    /**
     * Une ligne de l'historique d'un fournisseur.
     *
     * @param type           ACHAT ou PAIEMENT
     * @param amount         montant de l'achat ou du paiement, toujours positif
     * @param purchaseId     l'achat concerné : toujours pour un ACHAT, parfois pour un PAIEMENT
     * @param method         moyen de paiement, pour un PAIEMENT
     * @param fromCashDrawer vrai si le paiement est sorti du tiroir de la caisse
     */
    public record SupplierEntry(String type, long amount, String purchaseId, Long purchaseNumber,
            PaymentMethod method, boolean fromCashDrawer, String note, String createdAt) {
    }

    public record SupplierDetail(SupplierView supplier, List<SupplierEntry> entries) {
    }

    /**
     * @param quantity  nombre de conditionnements reçus
     * @param totalCost coût total de la ligne, en francs CFA
     */
    public record PurchaseItemRequest(@NotBlank String packagingId, @Positive long quantity,
            @PositiveOrZero long totalCost) {
    }

    /**
     * @param paidAmount     ce qui est payé à la livraison ; le reste devient une dette envers le fournisseur
     * @param paidMethod     moyen de paiement, obligatoire si paidAmount est positif
     * @param fromCashDrawer vrai si les espèces sortent du tiroir de la caisse ouverte
     */
    public record PurchaseRequest(
            @NotBlank String supplierId,
            @NotEmpty List<@Valid PurchaseItemRequest> items,
            @PositiveOrZero long paidAmount,
            PaymentMethod paidMethod,
            boolean fromCashDrawer,
            String note) {
    }

    public record PurchaseItemView(String productId, String productName, String packagingName, long factor,
            long quantity, long totalCost) {
    }

    /** @param paid total des paiements rattachés à cet achat */
    public record PurchaseView(String id, long number, String supplierId, String supplierName, long total,
            long paid, String note, String businessDate, String createdAt, List<PurchaseItemView> items) {
    }

    public record SupplierPaymentRequest(@Positive long amount, @NotNull PaymentMethod method,
            boolean fromCashDrawer, String note) {
    }
}
