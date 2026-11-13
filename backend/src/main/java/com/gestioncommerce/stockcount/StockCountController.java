package com.gestioncommerce.stockcount;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.stockcount.StockCountService.CountDetail;
import com.gestioncommerce.stockcount.StockCountService.CountSummary;
import com.gestioncommerce.stockcount.StockCountService.LineRequest;
import com.gestioncommerce.stockcount.StockCountService.StartRequest;

/**
 * Inventaire physique. Le propriétaire et la secrétaire comptent ;
 * seul le propriétaire valide, car la validation corrige le stock (voir AuthInterceptor).
 */
@RestController
@RequestMapping("/api/stock-counts")
public class StockCountController {

    private final StockCountService counts;

    public StockCountController(StockCountService counts) {
        this.counts = counts;
    }

    @GetMapping
    List<CountSummary> list() {
        return counts.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CountDetail start(@RequestBody(required = false) StartRequest request) {
        return counts.start(request);
    }

    @GetMapping("/{id}")
    CountDetail get(@PathVariable String id) {
        return counts.get(id);
    }

    /** Enregistre ou remplace le comptage d'un produit, en unités de base. */
    @PutMapping("/{id}/lines/{productId}")
    CountDetail count(@PathVariable String id, @PathVariable String productId, @RequestBody LineRequest request) {
        return counts.count(id, productId, request);
    }

    @DeleteMapping("/{id}/lines/{productId}")
    CountDetail removeLine(@PathVariable String id, @PathVariable String productId) {
        return counts.removeLine(id, productId);
    }

    @PostMapping("/{id}/validate")
    CountDetail validate(@PathVariable String id) {
        return counts.validate(id);
    }

    @PostMapping("/{id}/abandon")
    CountDetail abandon(@PathVariable String id) {
        return counts.abandon(id);
    }
}
