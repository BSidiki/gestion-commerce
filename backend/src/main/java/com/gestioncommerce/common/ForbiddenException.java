package com.gestioncommerce.common;

/** Opération réservée à un autre rôle. Renvoyée en 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
