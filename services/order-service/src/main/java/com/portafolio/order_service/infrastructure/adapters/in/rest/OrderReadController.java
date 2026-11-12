package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.in.ReadOrdersUseCase;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Reason;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/orders")
public class OrderReadController {
    private final ReadOrdersUseCase orders;

    @GetMapping("/me")
    public PageResponse mine(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(orders.mine(jwt.getSubject(), page, size));
    }

    @GetMapping("/{id}")
    public DetailResponse detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return orders.mineById(jwt.getSubject(), id)
                .map(DetailResponse::from)
                .orElseThrow(() -> new OrderException(Reason.ORDER_NOT_FOUND, "Pedido no encontrado"));
    }
}
