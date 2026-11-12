package com.portafolio.order_service.application.ports.in;

import java.util.UUID;

public record CheckoutResult(UUID attemptId, String checkoutUrl, boolean reused) {}
