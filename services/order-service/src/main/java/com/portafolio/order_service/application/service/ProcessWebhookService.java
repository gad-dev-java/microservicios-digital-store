package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.in.ProcessWebhookUseCase;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import com.portafolio.order_service.application.ports.out.WebhookReceiptPort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessWebhookService implements ProcessWebhookUseCase {
    private final WebhookReceiptPort receipts;
    private final PaymentGatewayPort paymentGateway;
    private final PaymentConfirmationService confirmation;

    @Override
    public void process(
            String notificationId,
            String providerOrderId,
            String action,
            String payload,
            Boolean notificationLiveMode) {
        if (receipts.registration(notificationId, providerOrderId, action, payload)) {
            return;
        }

        try {
            ProviderOrderSnapshot provider = paymentGateway.getOrder(providerOrderId);
            confirmation.apply(notificationId, provider, notificationLiveMode);
        } catch (RuntimeException exception) {
            receipts.markFailed(notificationId);
            if (exception instanceof OrderException orderException) {
                throw orderException;
            }
            throw new OrderException(Reason.PAYMENT_PROVIDER_UNAVAILABLE, "No se pudo confirmar la orden notificada");
        }
    }
}
