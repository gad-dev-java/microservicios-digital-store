package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.application.ports.in.ReadProductForOrderUseCase;
import com.portafolio.catalog_service.domain.models.Product;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/internal/catalog/products")
public class InternalProductController {
    private final ReadProductForOrderUseCase products;

    @GetMapping("/{id}")
    public SnapshotResponse get(@PathVariable UUID id) {
        Product product = products.getPublished(id);
        return new SnapshotResponse(product.id(), product.sku(), product.title(), product.price(), product.currency());
    }

    public record SnapshotResponse(UUID id, String sku, String title, BigDecimal price, String currency) {}
}
