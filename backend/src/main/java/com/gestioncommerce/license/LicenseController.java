package com.gestioncommerce.license;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.license.LicenseService.LicenseStatus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api/license")
public class LicenseController {

    public record ActivateRequest(@NotBlank String license) {
    }

    private final LicenseService licenses;

    public LicenseController(LicenseService licenses) {
        this.licenses = licenses;
    }

    @GetMapping
    LicenseStatus status() {
        return licenses.status();
    }

    /** Enregistre la licence collée par le commerçant. Reste possible en lecture seule. */
    @PostMapping
    LicenseStatus activate(@Valid @RequestBody ActivateRequest request) {
        return licenses.activate(request.license());
    }
}
