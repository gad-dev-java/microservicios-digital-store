package com.portafolio.order_service.application.ports.out;

import java.time.Instant;
import java.util.UUID;

public record ReconcileCandidate(UUID id, String status, String providerOrderId, Instant createdAt, int attempts) {}
