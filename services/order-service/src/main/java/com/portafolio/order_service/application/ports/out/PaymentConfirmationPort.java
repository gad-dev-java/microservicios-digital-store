package com.portafolio.order_service.application.ports.out;

import java.util.Optional;
import java.util.UUID;

public interface PaymentConfirmationPort {
    Optional<Attempt> lockAttempt(String providerOrderId, String externalReference);

    StoreOrder lockOrder(UUID orderId);

    void markReview(Attempt attempt, ProviderOrderSnapshot provider, String reason);

    void updateProviderStatus(Attempt attempt, ProviderOrderSnapshot provider);

    void approveAttempt(Attempt attempt, ProviderOrderSnapshot provider, String reviewReason);

    void markOrderPaid(UUID orderId);

    int grantEntitlements(StoreOrder order);

    void enqueueOrderPaid(StoreOrder order);
}
