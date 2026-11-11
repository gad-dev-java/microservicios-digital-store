package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import java.util.List;

public record ProductPageResponse(
        List<ProductSummaryResponse> items, int page, int size, long totalItems, int totalPages) {}
