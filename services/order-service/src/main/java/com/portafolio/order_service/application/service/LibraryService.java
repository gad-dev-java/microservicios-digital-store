package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.in.LibraryUseCase;
import com.portafolio.order_service.application.ports.out.CatalogDownloadPort;
import com.portafolio.order_service.application.ports.out.LibraryRepositoryPort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class LibraryService implements LibraryUseCase {
    private final LibraryRepositoryPort library;
    private final CatalogDownloadPort catalog;

    public LibraryService(LibraryRepositoryPort library, CatalogDownloadPort catalog) {
        this.library = library;
        this.catalog = catalog;
    }

    @Override
    public Page list(String buyerSub, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new OrderException(Reason.INVALID_ORDER_REQUEST, "Paginación inválida");
        }
        return library.findMine(buyerSub, page, size);
    }

    @Override
    public Download prepare(String buyerSub, UUID productId) {
        requireEntitlement(buyerSub, productId);
        return catalog.metadata(productId);
    }

    @Override
    public void stream(String buyerSub, UUID productId, long expectedSize, OutputStream output) throws IOException {
        requireEntitlement(buyerSub, productId);
        catalog.stream(productId, expectedSize, output);
    }

    private void requireEntitlement(String buyerSub, UUID productId) {
        if (!library.hasEntitlement(buyerSub, productId)) {
            throw new OrderException(Reason.LIBRARY_ITEM_NOT_FOUND, "Producto no encontrado en tu biblioteca");
        }
    }
}
