package com.gestioncommerce.suppliers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.suppliers.SupplierDtos.PurchaseRequest;
import com.gestioncommerce.suppliers.SupplierDtos.PurchaseView;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierDetail;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierPaymentRequest;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierRequest;
import com.gestioncommerce.suppliers.SupplierDtos.SupplierView;

import jakarta.validation.Valid;

/** Fournisseurs et achats. Réservés au propriétaire et à la secrétaire (voir AuthInterceptor). */
@RestController
@RequestMapping("/api")
public class SupplierController {

    private final SupplierService suppliers;

    public SupplierController(SupplierService suppliers) {
        this.suppliers = suppliers;
    }

    @GetMapping("/suppliers")
    List<SupplierView> list() {
        return suppliers.list();
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    SupplierView create(@Valid @RequestBody SupplierRequest request) {
        return suppliers.create(request);
    }

    @GetMapping("/suppliers/{id}")
    SupplierDetail get(@PathVariable String id) {
        return suppliers.get(id);
    }

    @PutMapping("/suppliers/{id}")
    SupplierView update(@PathVariable String id, @Valid @RequestBody SupplierRequest request) {
        return suppliers.update(id, request);
    }

    /** Paiement d'une dette envers le fournisseur. */
    @PostMapping("/suppliers/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    SupplierView pay(@PathVariable String id, @Valid @RequestBody SupplierPaymentRequest request) {
        return suppliers.pay(id, request);
    }

    /** Une livraison : entre en stock, et ce qui n'est pas payé devient une dette. */
    @PostMapping("/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    PurchaseView createPurchase(@Valid @RequestBody PurchaseRequest request) {
        return suppliers.createPurchase(request);
    }

    @GetMapping("/purchases/{id}")
    PurchaseView getPurchase(@PathVariable String id) {
        return suppliers.getPurchase(id);
    }
}
