package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.application.ports.in.BrowseCatalogUseCase;
import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.domain.models.Product;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final BrowseCatalogUseCase catalog;

    @GetMapping
    public ProductPageResponse search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String title = q.trim();

        if (title.length() > 100 || page < 0 || size < 1 || size > 50) {
            throw new InvalidCatalogQueryException(
                    "q admite hasta 100 caracteres; page debe ser >= 0 y size entre 1 y 50");
        }

        ProductPage result = catalog.search(title, page, size);
        return new ProductPageResponse(
                result.items().stream().map(ProductController::summary).toList(),
                result.page(),
                result.size(),
                result.totalItems(),
                result.totalPages());
    }

    @GetMapping("/{id}")
    public ProductDetailResponse get(@PathVariable UUID id) {
        Product product = catalog.getPublished(id);
        return new ProductDetailResponse(
                product.id(), product.title(), product.price(), product.currency(), product.description());
    }

    private static ProductSummaryResponse summary(Product product) {
        return new ProductSummaryResponse(product.id(), product.title(), product.price(), product.currency());
    }
}
