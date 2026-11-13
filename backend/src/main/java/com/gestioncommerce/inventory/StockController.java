package com.gestioncommerce.inventory;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.inventory.StockDtos.AdjustmentRequest;
import com.gestioncommerce.inventory.StockDtos.MovementView;
import com.gestioncommerce.inventory.StockDtos.ReceptionRequest;
import com.gestioncommerce.inventory.StockDtos.StockLevel;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class StockController {

    private final StockService stock;

    public StockController(StockService stock) {
        this.stock = stock;
    }

    @PostMapping("/stock/receptions")
    @ResponseStatus(HttpStatus.CREATED)
    StockLevel receive(@Valid @RequestBody ReceptionRequest request) {
        return stock.receive(request).forCurrentUser();
    }

    @PostMapping("/stock/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    StockLevel adjust(@Valid @RequestBody AdjustmentRequest request) {
        return stock.adjust(request).forCurrentUser();
    }

    @GetMapping("/products/{id}/movements")
    List<MovementView> movements(@PathVariable String id) {
        return stock.movementsOf(id);
    }
}
