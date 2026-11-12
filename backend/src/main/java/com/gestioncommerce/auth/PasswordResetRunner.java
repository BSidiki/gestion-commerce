package com.gestioncommerce.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Dépannage : redonner un mot de passe à un compte quand plus aucun propriétaire ne peut se connecter.
 *
 * Le logiciel étant arrêté, on le relance une fois avec
 *   --app.reset-password=nom-utilisateur:nouveau-mot-de-passe
 * Cela suppose d'avoir la main sur le PC de la boutique : c'est la même confiance que celle
 * accordée à qui peut ouvrir le dossier des données.
 */
@Component
public class PasswordResetRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetRunner.class);

    private final AuthService auth;
    private final String request;

    public PasswordResetRunner(AuthService auth, @Value("${app.reset-password:}") String request) {
        this.auth = auth;
        this.request = request;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (request == null || request.isBlank()) {
            return;
        }
        int separator = request.indexOf(':');
        if (separator <= 0 || separator == request.length() - 1) {
            log.warn("--app.reset-password ignoré : la forme attendue est nom-utilisateur:nouveau-mot-de-passe");
            return;
        }
        String username = request.substring(0, separator);
        try {
            auth.resetPasswordByUsername(username, request.substring(separator + 1));
            log.warn("Mot de passe du compte « {} » réinitialisé au démarrage.", username);
        } catch (RuntimeException e) {
            log.warn("Réinitialisation du mot de passe de « {} » impossible : {}", username, e.getMessage());
        }
    }
}
