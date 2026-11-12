package com.gestioncommerce.auth;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.CurrentUser.User;
import com.gestioncommerce.common.ForbiddenException;
import com.gestioncommerce.common.UnauthorizedException;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Qui a le droit de faire quoi.
 *
 *   - Tant qu'aucun compte n'existe : accès libre.
 *   - Ensuite : toute l'API exige une session, sauf de quoi se connecter.
 *   - Le vendeur vend, encaisse, reçoit la marchandise, note les dépenses, clôture la caisse, consulte.
 *   - La secrétaire fait de même et, en plus, crée et modifie le catalogue et voit les marges.
 *   - Le propriétaire fait tout, et lui seul : corrige le stock, annule une vente, vend sous le prix
 *     plancher, voit le journal, gère les comptes, la licence, les coordonnées de la boutique, et restaure
 *     ou protège les sauvegardes.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Set<String> PUBLIC = Set.of(
            "/api/health", "/api/auth/me", "/api/auth/login", "/api/auth/setup", "/api/auth/logout");

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /** Réservé au propriétaire, lecture comprise. */
    private static final Set<String> OWNER_ONLY = Set.of("/api/audit");

    private static final Set<String> OWNER_ONLY_WRITES = Set.of(
            "/api/stock/adjustments", "/api/license", "/api/backups/restore", "/api/backups/encryption",
            "/api/shop");

    /** Réservé au propriétaire et à la secrétaire, lecture comprise. */
    private static final Set<String> MANAGER_ONLY = Set.of("/api/reports/daily");

    private static final Set<String> MANAGER_ONLY_WRITES = Set.of("/api/products", "/api/categories");

    private static final Pattern SALE_CANCEL = Pattern.compile("/api/sales/[^/]+/cancel");

    private final AuthService auth;

    public AuthInterceptor(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        CurrentUser.clear();
        String path = request.getRequestURI();

        Optional<User> user = auth.authenticate(sessionToken(request));
        user.ifPresent(CurrentUser::set);

        if (PUBLIC.contains(path) || !auth.hasUsers()) {
            return true;
        }
        if (user.isEmpty()) {
            throw new UnauthorizedException("Connectez-vous pour continuer.");
        }
        boolean write = !READ_METHODS.contains(request.getMethod());
        if (!user.get().isOwner() && ownerOnly(write, path)) {
            throw new ForbiddenException("Cette opération est réservée au propriétaire.");
        }
        // Toute écriture sous /api/products/ modifie ou désactive un produit.
        boolean catalogWrite = write && (MANAGER_ONLY_WRITES.contains(path) || path.startsWith("/api/products/"));
        if (!user.get().isManager() && (MANAGER_ONLY.contains(path) || catalogWrite)) {
            throw new ForbiddenException("Cette opération est réservée au propriétaire et à la secrétaire.");
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        CurrentUser.clear();
    }

    private static boolean ownerOnly(boolean write, String path) {
        if (OWNER_ONLY.contains(path) || path.equals("/api/users") || path.startsWith("/api/users/")) {
            return true;
        }
        return write && (OWNER_ONLY_WRITES.contains(path) || SALE_CANCEL.matcher(path).matches());
    }

    private static String sessionToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthController.SESSION_COOKIE.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
