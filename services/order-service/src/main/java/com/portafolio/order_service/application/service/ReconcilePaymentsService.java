package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.out.CheckoutStorePort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import com.portafolio.order_service.application.ports.out.ProviderRateLimitException;
import com.portafolio.order_service.application.ports.out.ReconcileCandidate;
import com.portafolio.order_service.application.ports.out.ReconciliationStorePort;
import com.portafolio.order_service.domain.models.ProviderResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconcilePaymentsService {
    private static final Duration LIMIT = Duration.ofHours(24);
    private static final Duration PENDING_DELAY = Duration.ofMinutes(2);

    private final ReconciliationStorePort store;
    private final CheckoutStorePort checkoutStore;
    private final PaymentGatewayPort paymentGateway;
    private final PaymentConfirmationService confirmation;

    // No abrir una transacción que incluya llamadas HTTP.
    public void runBatch() {
        for (ReconcileCandidate candidate : store.claimDue(20)) {
            try {
                reconcile(candidate);
            } catch (ProviderRateLimitException limited) {
                long seconds = Math.max(
                        backoff(candidate).toSeconds(), limited.retryAfter().toSeconds());
                store.postpone(candidate.id(), Duration.ofSeconds(seconds));
            } catch (RuntimeException failure) {
                log.warn(
                        "Reconciliación pendiente: intento={}, tipo={}",
                        candidate.id(),
                        failure.getClass().getSimpleName());

                if (isOldCreating(candidate)) {
                    store.requireReview(candidate.id(), "UNRESOLVED_TIMEOUT");
                } else {
                    store.postpone(candidate.id(), backoff(candidate));
                }
            }
        }
    }

    private void reconcile(ReconcileCandidate candidate) {
        if ("PENDING".equals(candidate.status())) {
            if (candidate.providerOrderId() == null) {
                store.requireReview(candidate.id(), "MISSING_PROVIDER_ORDER_ID");
                return;
            }

            ProviderOrderSnapshot provider = paymentGateway.getOrder(candidate.providerOrderId());
            confirmation.applyFromReconciliation(provider);
            store.postpone(candidate.id(), PENDING_DELAY);
            return;
        }

        Instant begin = candidate.createdAt().minus(Duration.ofMinutes(10));
        Instant end = Instant.now().plus(Duration.ofMinutes(1));

        List<String> found = paymentGateway.findOrderIds(candidate.id(), begin, end);

        if (found.size() > 1) {
            store.requireReview(candidate.id(), "MULTIPLE_PROVIDER_ORDERS");
            return;
        }

        if (found.size() == 1) {
            String providerId = found.getFirst();
            ProviderOrderSnapshot provider = paymentGateway.getOrder(providerId);
            ProviderResult checkout = paymentGateway.checkoutFor(providerId);

            if (checkout.checkoutUrl() == null && !"processed".equals(provider.status())) {
                store.requireReview(candidate.id(), "MISSING_CHECKOUT_URL");
                return;
            }

            checkoutStore.complete(candidate.id(), checkout.providerOrderId(), checkout.checkoutUrl());
            confirmation.applyFromReconciliation(provider);
            store.postpone(candidate.id(), PENDING_DELAY);
            return;
        }

        if (isOldCreating(candidate)) {
            store.requireReview(candidate.id(), "UNRESOLVED_TIMEOUT");
            return;
        }

        // loadAttempt() recupera la MISMA clave, correo, importe y
        // líneas guardadas. create() arma el mismo cuerpo que HU-07.
        ProviderResult recreated = paymentGateway.create(store.loadAttempt(candidate.id()));
        checkoutStore.complete(candidate.id(), recreated.providerOrderId(), recreated.checkoutUrl());
        store.postpone(candidate.id(), PENDING_DELAY);
    }

    private boolean isOldCreating(ReconcileCandidate candidate) {
        return "CREATING".equals(candidate.status())
                && !candidate.createdAt().plus(LIMIT).isAfter(Instant.now());
    }

    private Duration backoff(ReconcileCandidate candidate) {
        long exponential = 30L * (1L << Math.min(candidate.attempts(), 6));
        long jitter = ThreadLocalRandom.current().nextLong(0, 16);
        return Duration.ofSeconds(Math.min(3600, exponential + jitter));
    }
}
