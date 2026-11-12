package com.gestioncommerce.cash;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.cash.CashDtos.CashSessionView;
import com.gestioncommerce.cash.CashDtos.CashStatus;
import com.gestioncommerce.cash.CashDtos.CloseRequest;
import com.gestioncommerce.cash.CashDtos.ExpenseRequest;
import com.gestioncommerce.cash.CashDtos.ExpenseView;
import com.gestioncommerce.cash.CashDtos.OpenRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class CashController {

    private final CashService cash;

    public CashController(CashService cash) {
        this.cash = cash;
    }

    @GetMapping("/cash/current")
    CashStatus current() {
        return cash.status();
    }

    @PostMapping("/cash/open")
    @ResponseStatus(HttpStatus.CREATED)
    CashSessionView open(@Valid @RequestBody OpenRequest request) {
        return cash.open(request);
    }

    @PostMapping("/cash/close")
    CashSessionView close(@Valid @RequestBody CloseRequest request) {
        return cash.close(request);
    }

    @GetMapping("/cash/sessions")
    List<CashSessionView> history() {
        return cash.history();
    }

    @PostMapping("/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    ExpenseView addExpense(@Valid @RequestBody ExpenseRequest request) {
        return cash.addExpense(request);
    }

    /** Dépenses d'une journée (aujourd'hui par défaut), la plus récente en premier. */
    @GetMapping("/expenses")
    List<ExpenseView> expenses(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return cash.expensesOf(date != null ? date : LocalDate.now());
    }
}
