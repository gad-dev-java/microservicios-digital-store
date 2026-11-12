package com.portafolio.order_service.infrastructure.adapters.in.rest;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateOrderRequest(@NotEmpty @Size(max = 10) List<@NotNull UUID> productIds) {}
