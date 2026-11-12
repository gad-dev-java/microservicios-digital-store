package com.portafolio.order_service.infrastructure.adapters.in.rest;

import java.util.UUID;

public record CheckoutResponse(UUID attemptId, String checkoutUrl, boolean reused) {}
