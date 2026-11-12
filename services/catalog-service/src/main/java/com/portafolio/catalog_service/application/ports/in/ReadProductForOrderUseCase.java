package com.portafolio.catalog_service.application.ports.in;

import com.portafolio.catalog_service.domain.models.Product;
import java.util.UUID;

public interface ReadProductForOrderUseCase {
    Product getPublished(UUID id);
}
