package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.in.Command;
import com.portafolio.order_service.application.ports.in.CreateOrderUseCase;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderController {
    private final CreateOrderUseCase orders;

    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateOrderRequest request) {
        String email = jwt.getClaimAsString("email");
        if (!Boolean.TRUE.equals(jwt.getClaim("email_verified")) || email == null || email.isBlank()) {
            throw new OrderException(Reason.EMAIL_NOT_VERIFIED, "Verifica tu correo antes de crear un pedido");
        }

        var result = orders.create(new Command(jwt.getSubject(), email, request.productIds(), key));

        OrderResponse body = OrderResponse.from(result.order());
        if (result.replayed()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/orders/" + body.id()))
                .body(body);
    }
}
