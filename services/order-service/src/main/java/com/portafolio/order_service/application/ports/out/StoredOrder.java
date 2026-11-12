package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Order;

public record StoredOrder(Order order, String requestHash) {}
