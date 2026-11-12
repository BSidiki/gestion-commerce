package com.gestioncommerce.customers;

import java.util.List;

import com.gestioncommerce.common.PaymentMethod;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public final class CustomerDtos {

    private CustomerDtos() {
    }

    public record CustomerRequest(@NotBlank String name, String phone) {
    }

    /** @param balance solde dû par le client, en francs CFA (négatif : le commerçant lui doit un avoir) */
    public record CustomerView(String id, String name, String phone, long balance) {
    }

    public record LedgerEntryView(String type, long amount, String saleId, String note, String createdAt) {
    }

    public record CustomerDetail(CustomerView customer, List<LedgerEntryView> ledger) {
    }

    public record RepaymentRequest(@Positive long amount, @NotNull PaymentMethod method, String note) {
    }
}
