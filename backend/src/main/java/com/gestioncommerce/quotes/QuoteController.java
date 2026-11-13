package com.gestioncommerce.quotes;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.quotes.QuoteService.QuoteRequest;
import com.gestioncommerce.quotes.QuoteService.QuoteView;

import jakarta.validation.Valid;

/** Devis. Ouverts à tous les comptes : un vendeur prépare un devis comme il prépare une vente. */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    @GetMapping
    List<QuoteView> list() {
        return quotes.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    QuoteView create(@Valid @RequestBody QuoteRequest request) {
        return quotes.create(request);
    }

    @GetMapping("/{id}")
    QuoteView get(@PathVariable String id) {
        return quotes.get(id);
    }

    @PostMapping("/{id}/cancel")
    QuoteView cancel(@PathVariable String id) {
        return quotes.cancel(id);
    }
}
