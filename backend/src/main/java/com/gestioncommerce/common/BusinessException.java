package com.gestioncommerce.common;

/** Règle de gestion non respectée : renvoyée au client en 422 avec un message lisible. */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
