package com.portafolio.catalog_service.application.ports.in;

import com.portafolio.catalog_service.domain.models.Product;
import java.util.List;

public record ProductPage(List<Product> items, int page, int size, long totalItems, int totalPages) {}
