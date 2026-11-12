package com.gestioncommerce.customers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.customers.CustomerDtos.CustomerDetail;
import com.gestioncommerce.customers.CustomerDtos.CustomerRequest;
import com.gestioncommerce.customers.CustomerDtos.CustomerView;
import com.gestioncommerce.customers.CustomerDtos.RepaymentRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CustomerView create(@Valid @RequestBody CustomerRequest request) {
        return customers.create(request);
    }

    /** withDebt=true : uniquement les clients qui doivent de l'argent. */
    @GetMapping
    List<CustomerView> list(@RequestParam(defaultValue = "false") boolean withDebt) {
        return customers.list(withDebt);
    }

    @GetMapping("/{id}")
    CustomerDetail get(@PathVariable String id) {
        return customers.get(id);
    }

    @PostMapping("/{id}/repayments")
    @ResponseStatus(HttpStatus.CREATED)
    CustomerDetail repay(@PathVariable String id, @Valid @RequestBody RepaymentRequest request) {
        return customers.repay(id, request);
    }
}
