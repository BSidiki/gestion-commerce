package com.gestioncommerce.audit;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.audit.AuditService.AuditEntry;

@RestController
public class AuditController {

    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    /** Réservé au propriétaire (voir AuthInterceptor). */
    @GetMapping("/api/audit")
    List<AuditEntry> recent() {
        return audit.recent();
    }
}
