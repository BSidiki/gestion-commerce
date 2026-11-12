package com.gestioncommerce.auth;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.gestioncommerce.auth.AuthService.Login;
import com.gestioncommerce.auth.AuthService.UserView;
import com.gestioncommerce.common.CurrentUser;
import com.gestioncommerce.common.CurrentUser.User;
import com.gestioncommerce.common.UnauthorizedException;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
@RequestMapping("/api")
public class AuthController {

    /** Nom du cookie de session. Lu aussi par AuthInterceptor. */
    public static final String SESSION_COOKIE = "GC_SESSION";

    public record SetupRequest(@NotBlank String username, @NotBlank String displayName, @NotBlank String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record OwnPasswordRequest(@NotBlank String currentPassword, @NotBlank String newPassword) {
    }

    public record CreateUserRequest(@NotBlank String username, @NotBlank String displayName, @NotBlank String role,
            @NotBlank String password) {
    }

    public record ResetPasswordRequest(@NotBlank String password) {
    }

    public record ActiveRequest(boolean active) {
    }

    /**
     * @param setupRequired vrai tant qu'aucun compte n'existe : l'interface fait créer celui du propriétaire
     * @param user          la personne connectée, ou null
     */
    public record Me(boolean setupRequired, User user) {
    }

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    // ---------- Session ----------

    @GetMapping("/auth/me")
    Me me() {
        return new Me(!auth.hasUsers(), CurrentUser.get());
    }

    @PostMapping("/auth/setup")
    @ResponseStatus(HttpStatus.CREATED)
    Me setup(@Valid @RequestBody SetupRequest request, HttpServletResponse response) {
        Login login = auth.setup(request.username(), request.displayName(), request.password());
        setSessionCookie(response, login.token());
        return new Me(false, login.user());
    }

    @PostMapping("/auth/login")
    Me login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        Login login = auth.login(request.username(), request.password());
        setSessionCookie(response, login.token());
        return new Me(false, login.user());
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@CookieValue(name = SESSION_COOKIE, required = false) String token, HttpServletResponse response) {
        auth.logout(token);
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, "")
                .httpOnly(true).sameSite("Strict").path("/").maxAge(0).build().toString());
    }

    @PostMapping("/auth/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changeOwnPassword(@Valid @RequestBody OwnPasswordRequest request) {
        User user = CurrentUser.get();
        if (user == null) {
            throw new UnauthorizedException("Connectez-vous pour changer votre mot de passe.");
        }
        auth.changeOwnPassword(user, request.currentPassword(), request.newPassword());
    }

    // ---------- Comptes (réservé au propriétaire, voir AuthInterceptor) ----------

    @GetMapping("/users")
    List<UserView> users() {
        return auth.listUsers();
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    UserView createUser(@Valid @RequestBody CreateUserRequest request) {
        return auth.createUser(request.username(), request.displayName(), request.role(), request.password());
    }

    @PostMapping("/users/{id}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest request) {
        auth.resetPassword(id, request.password());
    }

    @PostMapping("/users/{id}/active")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setActive(@PathVariable String id, @RequestBody ActiveRequest request) {
        auth.setActive(id, request.active());
    }

    /**
     * Cookie de session : invisible pour les scripts de la page (HttpOnly), jamais envoyé
     * depuis un autre site (SameSite=Strict), effacé à la fermeture du navigateur.
     */
    private static void setSessionCookie(HttpServletResponse response, String token) {
        response.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true).sameSite("Strict").path("/").build().toString());
    }
}
