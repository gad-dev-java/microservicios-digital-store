package com.portafolio.order_service.domain.models;

import java.util.List;

public record Detail(Summary order, List<OrderItem> items) {}
