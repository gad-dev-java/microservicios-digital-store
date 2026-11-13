package com.portafolio.catalog_service.application.ports.out;

public class AssetStorageException extends RuntimeException {
    public AssetStorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public AssetStorageException(String message) {
        super(message);
    }
}
