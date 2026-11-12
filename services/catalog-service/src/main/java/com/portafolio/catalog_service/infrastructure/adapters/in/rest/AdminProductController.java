package com.portafolio.catalog_service.infrastructure.adapters.in.rest;

import com.portafolio.catalog_service.application.ports.in.ManageProductsUseCase;
import com.portafolio.catalog_service.application.ports.in.ProductPage;
import com.portafolio.catalog_service.domain.models.Product;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/products")
public class AdminProductController {
    private final ManageProductsUseCase products;

    @GetMapping
    public AdminProductPageResponse list(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new InvalidCatalogQueryException("Paginación inválida");
        }
        ProductPage result = products.list(page, size);
        return new AdminProductPageResponse(
                result.items().stream().map(AdminProductResponse::from).toList(),
                result.page(),
                result.size(),
                result.totalItems(),
                result.totalPages());
    }

    @GetMapping("/{id}")
    public AdminProductResponse get(@PathVariable UUID id) {
        return AdminProductResponse.from(products.get(id));
    }

    @PostMapping
    public ResponseEntity<AdminProductResponse> create(@Valid @RequestBody CreateDraftRequest request) {
        Product product = products.createDraft(request.sku(), request.title(), request.description(), request.price());
        return ResponseEntity.created(URI.create("/api/admin/products/" + product.id()))
                .body(AdminProductResponse.from(product));
    }

    @PostMapping(path = "/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdminProductResponse upload(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        try (var source = file.getInputStream()) {
            return AdminProductResponse.from(
                    products.upload(id, source, file.getOriginalFilename(), file.getContentType(), file.getSize()));
        }
    }

    @PostMapping("/{id}/publish")
    public AdminProductResponse publish(@PathVariable UUID id) {
        return AdminProductResponse.from(products.publish(id));
    }

    @PostMapping("/{id}/archive")
    public AdminProductResponse archive(@PathVariable UUID id) {
        return AdminProductResponse.from(products.archive(id));
    }

    @PutMapping("/{id}")
    public AdminProductResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateProductRequest request) {
        return AdminProductResponse.from(
                products.updateDetails(id, request.title(), request.description(), request.price()));
    }
}
