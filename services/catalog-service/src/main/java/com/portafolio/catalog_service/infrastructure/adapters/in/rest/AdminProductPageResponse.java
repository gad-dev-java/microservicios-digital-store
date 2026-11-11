package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import java.util.List;

public record AdminProductPageResponse(
        List<AdminProductResponse> items, int page, int size, long totalItems, int totalPages) {}
