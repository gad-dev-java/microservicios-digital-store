package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.in.ReadOrdersUseCase;
import com.portafolio.order_service.application.ports.out.OrderQueryPort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Detail;
import com.portafolio.order_service.domain.models.Page;
import com.portafolio.order_service.domain.models.Reason;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReadOrdersService implements ReadOrdersUseCase {
    private final OrderQueryPort queries;

    @Override
    public Page mine(String buyerSub, int page, int size) {
        validatePage(page, size);
        return queries.findMine(buyerSub, page, size);
    }

    @Override
    public Optional<Detail> mineById(String buyerSub, UUID orderId) {
        return queries.findMineById(buyerSub, orderId);
    }

    @Override
    public Page all(int page, int size) {
        validatePage(page, size);
        return queries.findAll(page, size);
    }

    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new OrderException(Reason.INVALID_ORDER_REQUEST, "page debe ser >= 0 y size debe estar entre 1 y 50");
        }
    }
}
