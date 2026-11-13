package com.gestioncommerce.reports;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.reports.ReportService.CreditReport;
import com.gestioncommerce.reports.ReportService.PeriodReport;
import com.gestioncommerce.reports.ReportService.StockReport;

/** Rapports de gestion. Réservés au propriétaire et à la secrétaire (voir AuthInterceptor). */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    /** Activité entre deux dates comprises. Sans dates : les 30 derniers jours, aujourd'hui compris. */
    @GetMapping("/period")
    PeriodReport period(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now();
        return reports.period(from != null ? from : end.minusDays(29), end);
    }

    @GetMapping("/stock")
    StockReport stock(@RequestParam(defaultValue = "30") int dormantDays) {
        return reports.stock(dormantDays, LocalDate.now());
    }

    @GetMapping("/credits")
    CreditReport credits() {
        return reports.credits(LocalDate.now());
    }
}
