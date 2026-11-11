package com.portafolio.catalog_service.domain.models;

public record AssetMetadata(String key, String fileName, String contentType, long size, String sha256) {}
