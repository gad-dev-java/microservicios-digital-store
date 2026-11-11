package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductDetailResponse(UUID id, String title, BigDecimal price, String currency, String description) {}
