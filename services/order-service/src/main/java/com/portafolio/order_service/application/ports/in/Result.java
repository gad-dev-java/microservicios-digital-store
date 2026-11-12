package com.portafolio.order_service.application.ports.in;

import com.portafolio.order_service.domain.models.Order;

public record Result(Order order, boolean replayed) {}
