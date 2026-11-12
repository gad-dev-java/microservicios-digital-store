package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Detail;
import com.portafolio.order_service.domain.models.Page;
import java.util.Optional;
import java.util.UUID;

public interface OrderQueryPort {
    Page findMine(String buyerSub, int page, int size);

    Optional<Detail> findMineById(String buyerSub, UUID orderId);

    Page findAll(int page, int size);
}
