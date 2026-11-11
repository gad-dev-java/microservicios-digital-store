package com.portafolio.catalog_service.domain.exceptions;

public class InvalidProductStateException extends RuntimeException {
    public InvalidProductStateException(String message) {
        super(message);
    }
}
