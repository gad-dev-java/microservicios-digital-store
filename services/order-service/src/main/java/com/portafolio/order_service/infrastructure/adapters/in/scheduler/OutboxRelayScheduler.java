package com.portafolio.order_service.infrastructure.adapters.in.scheduler;

import com.portafolio.order_service.application.service.PublishOutboxService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelayScheduler {
    private final PublishOutboxService relay;

    @Scheduled(
            fixedDelayString = "${outbox.relay.fixed-delay-ms:2000}",
            initialDelayString = "${outbox.relay.initial-delay-ms:5000}")
    public void publishDue() {
        relay.runBatch();
    }
}
