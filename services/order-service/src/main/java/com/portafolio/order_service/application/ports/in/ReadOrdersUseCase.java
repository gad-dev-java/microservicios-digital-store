package com.portafolio.order_service.application.ports.in;

import com.portafolio.order_service.domain.models.Detail;
import com.portafolio.order_service.domain.models.Page;
import java.util.Optional;
import java.util.UUID;

public interface ReadOrdersUseCase {
    Page mine(String buyerSub, int page, int size);

    Optional<Detail> mineById(String buyerSub, UUID orderId);

    Page all(int page, int size);
}
