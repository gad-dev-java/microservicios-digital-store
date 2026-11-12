package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Order;
import java.util.Optional;
import java.util.UUID;

public interface OrderStorePort {
    Optional<StoredOrder> findByIdempotency(String actorSub, String key);

    boolean ownsProduct(String buyerSub, UUID productId);

    void insert(Order order, String key, String requestHash);
}
