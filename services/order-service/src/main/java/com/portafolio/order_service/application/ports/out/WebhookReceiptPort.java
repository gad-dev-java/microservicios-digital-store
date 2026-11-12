package com.portafolio.order_service.application.ports.out;

public interface WebhookReceiptPort {
    boolean registration(String notificationId, String providerOrderId, String action, String payload);

    void markReceived(String notificationId);

    void markFailed(String notificationId);

    void markProcessed(String notificationId);
}
