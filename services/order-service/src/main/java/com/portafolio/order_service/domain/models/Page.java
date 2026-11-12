package com.portafolio.order_service.domain.models;

import java.util.List;

public record Page(List<Summary> items, int page, int size, long totalItems, long totalPages) {}
