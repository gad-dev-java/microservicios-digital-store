package com.portafolio.order_service.application.ports.out;

import java.util.UUID;

public interface CatalogPort {
    CatalogProduct getPublished(UUID id);
}
