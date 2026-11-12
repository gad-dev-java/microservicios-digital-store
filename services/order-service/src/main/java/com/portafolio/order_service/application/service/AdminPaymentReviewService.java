package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.out.AdminPaymentReviewPort;
import com.portafolio.order_service.application.ports.out.PaymentGatewayPort;
import com.portafolio.order_service.application.ports.out.ProviderOrderSnapshot;
import com.portafolio.order_service.application.ports.out.ReviewAttempt;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AdminPaymentReviewService {
    private final AdminPaymentReviewPort reviews;
    private final PaymentGatewayPort paymentGateway;
    private final PaymentConfirmationService confirmation;

    public List<ReviewAttempt> history(UUID orderId) {
        return reviews.history(orderId);
    }

    // La llamada HTTP al proveedor queda fuera de una transacción de PostgreSQL.
    public void reconcile(UUID attemptId) {
        ReviewAttempt attempt = requireAttempt(attemptId);
        if (!"REVIEW_REQUIRED".equals(attempt.status())) {
            throw conflict("El intento ya no está en revisión");
        }

        String providerId = attempt.providerOrderId();

        if (providerId == null) {
            Instant begin = attempt.createdAt().minus(Duration.ofMinutes(10));
            Instant end = Instant.now().plus(Duration.ofMinutes(1));

            List<String> found = paymentGateway.findOrderIds(attemptId, begin, end);

            if (found.isEmpty()) {
                return; // No encontrarlo no demuestra que nunca existió.
            }
            if (found.size() > 1) {
                reviews.changeReviewReason(attemptId, "MULTIPLE_PROVIDER_ORDERS");
                return;
            }
            providerId = found.getFirst();
        }

        ProviderOrderSnapshot provider = paymentGateway.getOrder(providerId);

        if (!providerId.equals(provider.id()) || !attemptId.toString().equals(provider.externalReference())) {
            reviews.changeReviewReason(attemptId, "REFERENCE_MISMATCH");
            return;
        }

        // Reutiliza las verificaciones de referencia, aplicación, importe,
        // moneda y estado, además del bloqueo y los efectos idempotentes.
        confirmation.applyFromReconciliation(provider);
    }

    @Transactional
    public void resolve(
            UUID attemptId, String adminSub, String resolution, String reason, boolean acknowledgeLatePaymentRisk) {
        if (!"ERROR".equals(resolution)
                || reason == null
                || reason.isBlank()
                || reason.trim().length() > 1000
                || !acknowledgeLatePaymentRisk) {
            throw new OrderException(
                    Reason.INVALID_REVIEW_RESOLUTION, "Indica ERROR, un motivo y acepta el riesgo de pago tardío");
        }

        ReviewAttempt attempt = reviews.lock(attemptId)
                .orElseThrow(
                        () -> new OrderException(Reason.PAYMENT_ATTEMPT_NOT_FOUND, "Intento de pago no encontrado"));

        if (!"REVIEW_REQUIRED".equals(attempt.status())
                || !"UNRESOLVED_TIMEOUT".equals(attempt.reviewReason())
                || attempt.providerOrderId() != null) {
            throw conflict("Solo se puede cerrar un pago incierto sin orden conocida");
        }

        int updated = reviews.resolve(attemptId, adminSub, reason.trim());

        if (updated != 1) {
            throw conflict("El intento cambió durante la resolución");
        }
    }

    private ReviewAttempt requireAttempt(UUID attemptId) {
        return reviews.find(attemptId)
                .orElseThrow(
                        () -> new OrderException(Reason.PAYMENT_ATTEMPT_NOT_FOUND, "Intento de pago no encontrado"));
    }

    private OrderException conflict(String message) {
        return new OrderException(Reason.PAYMENT_REVIEW_CONFLICT, message);
    }
}
