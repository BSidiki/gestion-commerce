package com.gestioncommerce.common;

/** Connexion requise ou identifiants incorrects. Renvoyée en 401. */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
