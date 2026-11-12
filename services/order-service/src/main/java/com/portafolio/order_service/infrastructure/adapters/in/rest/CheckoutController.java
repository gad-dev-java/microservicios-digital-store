package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.in.CheckoutOrderUseCase;
import com.portafolio.order_service.application.ports.in.CheckoutResult;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class CheckoutController {
    private final CheckoutOrderUseCase checkout;

    @PostMapping("/{id}/checkout")
    public CheckoutResponse open(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        CheckoutResult result = checkout.checkout(id, jwt.getSubject());
        return new CheckoutResponse(result.attemptId(), result.checkoutUrl(), result.reused());
    }
}
