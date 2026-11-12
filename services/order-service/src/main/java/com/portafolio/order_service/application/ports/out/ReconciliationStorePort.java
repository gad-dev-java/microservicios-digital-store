package com.portafolio.order_service.application.ports.out;

import com.portafolio.order_service.domain.models.Attempt;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface ReconciliationStorePort {
    List<ReconcileCandidate> claimDue(int limit);

    Attempt loadAttempt(UUID attemptId);

    void postpone(UUID attemptId, Duration delay);

    void requireReview(UUID attemptId, String reason);
}
