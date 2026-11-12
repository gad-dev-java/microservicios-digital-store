package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.out.ReviewAttempt;
import com.portafolio.order_service.application.service.AdminPaymentReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin")
public class AdminPaymentReviewController {
    private final AdminPaymentReviewService reviews;

    @GetMapping("/orders/{orderId}/payment-attempts")
    public List<AttemptResponse> history(@PathVariable UUID orderId) {
        return reviews.history(orderId).stream().map(AttemptResponse::from).toList();
    }

    @PostMapping("/payment-attempts/{id}/reconcile")
    public ResponseEntity<Void> reconcile(@PathVariable UUID id) {
        reviews.reconcile(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payment-attempts/{id}/resolve")
    public ResponseEntity<Void> resolve(
            @PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ResolveRequest request) {
        reviews.resolve(
                id,
                jwt.getSubject(),
                request.resolution(),
                request.reason(),
                Boolean.TRUE.equals(request.acknowledgeLatePaymentRisk()));
        return ResponseEntity.noContent().build();
    }

    public record ResolveRequest(
            @NotBlank @Pattern(regexp = "ERROR") String resolution,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull @AssertTrue Boolean acknowledgeLatePaymentRisk) {}

    public record AttemptResponse(
            UUID id,
            UUID orderId,
            String providerOrderId,
            String status,
            String providerStatus,
            String providerStatusDetail,
            String reviewReason,
            String resolutionReason,
            String resolvedBy,
            Instant createdAt,
            Instant updatedAt,
            Instant resolvedAt) {
        static AttemptResponse from(ReviewAttempt attempt) {
            return new AttemptResponse(
                    attempt.id(),
                    attempt.orderId(),
                    attempt.providerOrderId(),
                    attempt.status(),
                    attempt.providerStatus(),
                    attempt.providerStatusDetail(),
                    attempt.reviewReason(),
                    attempt.resolutionReason(),
                    attempt.resolvedBy(),
                    attempt.createdAt(),
                    attempt.updatedAt(),
                    attempt.resolvedAt());
        }
    }
}
