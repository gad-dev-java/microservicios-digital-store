package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Attempt;
import com.portafolio.order_service.domain.models.ProviderResult;

public interface PaymentGatewayPort {
    void requireConfigured();

    ProviderResult create(Attempt attempt);

    ProviderOrderSnapshot getOrder(String providerOrderId);
}
