package com.portafolio.order_service.infrastructure.adapters.out;

import java.util.UUID;

public record ActiveAttempt(UUID id, String status, String checkoutUrl) {}
