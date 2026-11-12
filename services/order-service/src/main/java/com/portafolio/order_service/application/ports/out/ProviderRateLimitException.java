package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.time.Duration;

public class ProviderRateLimitException extends OrderException {
    private final Duration retryAfter;

    public ProviderRateLimitException(Duration retryAfter) {
        super(Reason.PAYMENT_PROVIDER_UNAVAILABLE, "Mercado Pago limitó temporalmente las solicitudes");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
