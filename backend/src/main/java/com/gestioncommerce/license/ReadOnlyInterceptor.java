package com.gestioncommerce.license;

import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.gestioncommerce.common.ReadOnlyException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Essai terminé ou licence expirée : toute écriture est refusée, toute lecture reste possible.
 * Deux écritures restent permises pour ne jamais retenir les données en otage ni bloquer le
 * renouvellement : enregistrer une licence et faire une sauvegarde.
 */
@Component
public class ReadOnlyInterceptor implements HandlerInterceptor {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> ALWAYS_ALLOWED = Set.of("/api/license", "/api/backups");

    private final LicenseService licenses;

    public ReadOnlyInterceptor(LicenseService licenses) {
        this.licenses = licenses;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (READ_METHODS.contains(request.getMethod()) || ALWAYS_ALLOWED.contains(request.getRequestURI())) {
            return true;
        }
        if (licenses.isReadOnly()) {
            throw new ReadOnlyException();
        }
        return true;
    }
}
