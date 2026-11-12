package com.portafolio.order_service.application.ports.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminPaymentReviewPort {
    Optional<ReviewAttempt> find(UUID attemptId);

    Optional<ReviewAttempt> lock(UUID attemptId);

    List<ReviewAttempt> history(UUID orderId);

    void changeReviewReason(UUID attemptId, String reason);

    int resolve(UUID attemptId, String adminSub, String reason);
}
