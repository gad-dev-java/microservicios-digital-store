package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.in.CheckoutOrderUseCase;
import com.portafolio.order_service.application.ports.in.CheckoutResult;
import com.portafolio.order_service.application.ports.out.CheckoutStorePort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.domain.models.ProviderResult;
import com.portafolio.order_service.domain.models.Start;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CheckoutOrderService implements CheckoutOrderUseCase {
    private final CheckoutStorePort store;
    private final PaymentGatewayPort paymentGateway;

    @Override
    public CheckoutResult checkout(UUID orderId, String buyerSub) {
        paymentGateway.requireConfigured();

        // reserve() termina y confirma su transacción antes de llamar al proveedor.
        Start start = store.reserve(orderId, buyerSub);

        if (start.existingCheckoutUrl() != null) {
            return new CheckoutResult(start.attempt().id(), start.existingCheckoutUrl(), true);
        }

        ProviderResult providerOrder = paymentGateway.create(start.attempt());

        store.complete(start.attempt().id(), providerOrder.providerOrderId(), providerOrder.checkoutUrl());

        return new CheckoutResult(start.attempt().id(), providerOrder.checkoutUrl(), false);
    }
}
