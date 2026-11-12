package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.in.ProcessWebhookUseCase;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
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

    @Override
    public void process(String notificationId, String providerOrderId, String action, String payload) {
        if (receipts.registration(notificationId, providerOrderId, action, payload)) {
            return;
        }

        try {
            paymentGateway.verifyOrderExists(providerOrderId);
        } catch (RuntimeException exception) {
            receipts.markFailed(notificationId);
            throw new OrderException(Reason.PAYMENT_PROVIDER_UNAVAILABLE, "No se pudo verificar la orden notificada");
        }

        // HU-08 confirma la recepción y consulta.
        // HU-09 aplicará el pago y entonces cambiará el recibo a PROCESSED.
        receipts.markReceived(notificationId);
    }
}
