package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Start;
import java.util.UUID;

public interface CheckoutStorePort {
    Start reserve(UUID orderId, String buyerSub);

    void complete(UUID attemptId, String providerOrderId, String checkoutUrl);
}
