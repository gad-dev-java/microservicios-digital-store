package com.portafolio.order_service.application.ports.in;

import java.util.List;
import java.util.UUID;

public record Command(String buyerSub, String buyerEmail, List<UUID> productIds, String idempotencyKey) {}
