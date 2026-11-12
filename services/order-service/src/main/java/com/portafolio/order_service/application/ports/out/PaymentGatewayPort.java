package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.ProviderResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PaymentGatewayPort {
    void requireConfigured();

    ProviderResult create(Attempt attempt);

    ProviderOrderSnapshot getOrder(String providerOrderId);

    List<String> findOrderIds(UUID externalReference, Instant begin, Instant end);

    ProviderResult checkoutFor(String providerOrderId);
}
