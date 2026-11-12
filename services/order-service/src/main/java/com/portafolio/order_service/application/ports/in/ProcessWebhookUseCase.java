package com.portafolio.order_service.application.ports.in;

public interface ProcessWebhookUseCase {
    void process(String notificationId, String providerOrderId, String action, String payload);
}
