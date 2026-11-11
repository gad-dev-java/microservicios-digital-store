package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

public class InvalidCatalogQueryException extends RuntimeException {
    public InvalidCatalogQueryException(String message) {
        super(message);
    }
}
