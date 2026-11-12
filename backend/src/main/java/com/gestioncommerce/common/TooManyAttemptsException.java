package com.gestioncommerce.common;

/** Trop d'essais de connexion ratés. Renvoyée en 429. */
public class TooManyAttemptsException extends RuntimeException {

    public TooManyAttemptsException(String message) {
        super(message);
    }
}
