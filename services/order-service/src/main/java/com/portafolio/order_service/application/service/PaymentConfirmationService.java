package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.out.*;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfirmationService {
    private final PaymentConfirmationPort store;
    private final WebhookReceiptPort receipts;
    private final String expectedApplicationId;
    private final boolean expectedLiveMode;

    public PaymentConfirmationService(
            PaymentConfirmationPort store,
            WebhookReceiptPort receipts,
            @Value("${MERCADOPAGO_APPLICATION_ID:}") String expectedApplicationId,
            @Value("${MERCADOPAGO_EXPECTED_LIVE_MODE:false}") boolean expectedLiveMode) {
        this.store = store;
        this.receipts = receipts;
        this.expectedApplicationId = expectedApplicationId;
        this.expectedLiveMode = expectedLiveMode;
    }

    @Transactional
    public void apply(String notificationId, ProviderOrderSnapshot provider, Boolean notificationLiveMode) {
        if (expectedApplicationId.isBlank()) {
            throw new OrderException(
                    Reason.PAYMENT_PROVIDER_UNAVAILABLE, "Falta configurar la aplicación de Mercado Pago");
        }

        Attempt attempt = store.lockAttempt(provider.id(), provider.externalReference())
                .orElseThrow(() -> new OrderException(
                        Reason.PAYMENT_PROVIDER_UNAVAILABLE, "No se encontró el intento del pago notificado"));

        StoreOrder order = store.lockOrder(attempt.orderId());

        if ("APPROVED".equals(attempt.status())) {
            markReceiptProcessed(notificationId);
            return;
        }

        String mismatch = mismatch(attempt, order, provider, notificationLiveMode);
        if (mismatch != null) {
            store.markReview(attempt, provider, mismatch);
            markReceiptProcessed(notificationId);
            return;
        }

        if ("processed".equals(provider.status()) && !"accredited".equals(provider.statusDetail())) {
            store.markReview(attempt, provider, "STATUS_MISMATCH");
            markReceiptProcessed(notificationId);
            return;
        }

        // Una discrepancia previa sigue bloqueada hasta su revisión.
        // UNRESOLVED_TIMEOUT puede resolverse con un estado definitivo del proveedor.
        if ("REVIEW_REQUIRED".equals(attempt.status()) && !"UNRESOLVED_TIMEOUT".equals(attempt.reviewReason())) {
            markReceiptProcessed(notificationId);
            return;
        }

        if (!"processed".equals(provider.status())) {
            String terminalStatus = terminalStatus(provider);

            if (terminalStatus != null) {
                store.markTerminal(attempt, provider, terminalStatus);

                if ("REJECTED".equals(terminalStatus)) {
                    store.markOrderPaymentFailedIfNoActiveAttempt(order.id());
                }
            } else {
                // created, processing y action_required todavía no son definitivos.
                store.updateProviderStatus(attempt, provider);
            }

            markReceiptProcessed(notificationId);
            return;
        }

        if (provider.totalPaidAmount() == null || provider.totalPaidAmount().compareTo(order.totalAmount()) != 0) {
            store.markReview(attempt, provider, "AMOUNT_MISMATCH");
            markReceiptProcessed(notificationId);
            return;
        }

        if ("PAID".equals(order.status())) {
            store.approveAttempt(attempt, provider, "DUPLICATE_PAYMENT");
            markReceiptProcessed(notificationId);
            return;
        }

        int granted = store.grantEntitlements(order);
        store.markOrderPaid(order.id());
        store.approveAttempt(attempt, provider, granted < order.itemCount() ? "DUPLICATE_PURCHASE" : null);
        store.enqueueOrderPaid(order);
        markReceiptProcessed(notificationId);
    }

    @Transactional
    public void applyFromReconciliation(ProviderOrderSnapshot provider) {
        apply(null, provider, expectedLiveMode);
    }

    private void markReceiptProcessed(String notificationId) {
        if (notificationId != null) {
            receipts.markProcessed(notificationId);
        }
    }

    private String mismatch(
            Attempt attempt, StoreOrder order, ProviderOrderSnapshot provider, Boolean notificationLiveMode) {
        if (attempt.providerOrderId() != null && !attempt.providerOrderId().equals(provider.id())) {
            return "REFERENCE_MISMATCH";
        }
        if (!attempt.id().toString().equals(provider.externalReference())) {
            return "REFERENCE_MISMATCH";
        }
        if (!expectedApplicationId.equals(provider.applicationId())) {
            return "APPLICATION_MISMATCH";
        }
        if (!"PEN".equals(provider.currency())
                || !provider.currency().equals(attempt.currency())
                || !provider.currency().equals(order.currency())) {
            return "CURRENCY_MISMATCH";
        }
        if (provider.totalAmount() == null
                || provider.totalAmount().compareTo(attempt.amount()) != 0
                || provider.totalAmount().compareTo(order.totalAmount()) != 0) {
            return "AMOUNT_MISMATCH";
        }
        if (notificationLiveMode == null
                || notificationLiveMode != expectedLiveMode
                || (provider.liveMode() != null && provider.liveMode() != expectedLiveMode)) {
            return "LIVE_MODE_MISMATCH";
        }
        return null;
    }

    private String terminalStatus(ProviderOrderSnapshot provider) {
        String status = provider.status();
        String detail = provider.statusDetail();

        if ("expired".equals(status)
                || "canceled".equals(status)
                || ("failed".equals(status) && "expired".equals(detail))) {
            return "EXPIRED";
        }

        if ("failed".equals(status)) {
            return "REJECTED";
        }

        return null;
    }
}
