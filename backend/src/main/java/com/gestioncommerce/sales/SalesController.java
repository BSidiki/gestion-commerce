package com.gestioncommerce.sales;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.sales.SalesDtos.CancelRequest;
import com.gestioncommerce.sales.SalesDtos.DailyReport;
import com.gestioncommerce.sales.SalesDtos.SaleRequest;
import com.gestioncommerce.sales.SalesDtos.SaleView;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class SalesController {

    private final SalesService sales;

    public SalesController(SalesService sales) {
        this.sales = sales;
    }

    @PostMapping("/sales")
    @ResponseStatus(HttpStatus.CREATED)
    SaleView create(@Valid @RequestBody SaleRequest request) {
        return sales.create(request);
    }

    /** Ventes d'une journée (aujourd'hui par défaut), la plus récente en premier. */
    @GetMapping("/sales")
    List<SaleView> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return sales.listByDate(date != null ? date : LocalDate.now());
    }

    @GetMapping("/sales/{id}")
    SaleView get(@PathVariable String id) {
        return sales.get(id);
    }

    @PostMapping("/sales/{id}/cancel")
    SaleView cancel(@PathVariable String id, @Valid @RequestBody CancelRequest request) {
        return sales.cancel(id, request.reason());
    }

    @GetMapping("/reports/daily")
    DailyReport dailyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return sales.dailyReport(date != null ? date : LocalDate.now());
    }
}
