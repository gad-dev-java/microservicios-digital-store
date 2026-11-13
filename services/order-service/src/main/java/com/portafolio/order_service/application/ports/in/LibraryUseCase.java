package com.portafolio.order_service.application.ports.in;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LibraryUseCase {
    record Item(UUID productId, UUID orderId, String sku, String title, Instant grantedAt) {}

    record Page(List<Item> items, int page, int size, long totalItems, long totalPages) {}

    record Download(String fileName, String contentType, long size) {}

    Page list(String buyerSub, int page, int size);

    Download prepare(String buyerSub, UUID productId);

    void stream(String buyerSub, UUID productId, long expectedSize, OutputStream output) throws IOException;
}
