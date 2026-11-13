package com.portafolio.notification_service.infrastructure.adapters.in.scheduler;

import com.portafolio.notification_service.application.service.OrderPaidEmailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "app.notifications.sender",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EmailDeliveryScheduler {
    private final OrderPaidEmailService service;

    public EmailDeliveryScheduler(OrderPaidEmailService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${app.notifications.sender.poll-ms:5000}")
    public void sendDueEmails() {
        for (int i = 0; i < 20; i++) {
            service.sendNext();
        }
    }
}
