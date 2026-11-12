package com.portafolio.order_service.infrastructure.adapters.in.rest;

import com.portafolio.order_service.application.ports.in.ReadOrdersUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/orders")
public class AdminOrderReadController {
    private final ReadOrdersUseCase orders;

    @GetMapping
    public PageResponse all(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(orders.all(page, size));
    }
}
