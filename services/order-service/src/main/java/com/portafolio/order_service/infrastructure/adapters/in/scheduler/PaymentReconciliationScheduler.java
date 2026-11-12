package com.portafolio.order_service.infrastructure.adapters.in.scheduler;

import com.portafolio.order_service.application.service.ReconcilePaymentsService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {
    private final ReconcilePaymentsService reconciliation;

    @Scheduled(fixedDelayString = "${payments.reconciliation.interval-ms:60000}")
    public void run() {
        reconciliation.runBatch();
    }
}
