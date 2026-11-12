package com.portafolio.order_service.application.ports.in;

import java.util.UUID;

public interface CheckoutOrderUseCase {
    CheckoutResult checkout(UUID orderId, String buyerSub);
}
