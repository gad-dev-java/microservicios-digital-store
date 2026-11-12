package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.out.OrderStorePort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Order;
import com.portafolio.order_service.domain.models.OrderItem;
import com.portafolio.order_service.domain.models.Reason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PersistOrderService {
    private final OrderStorePort store;

    @Transactional
    public void persist(Order order, String key, String hash) {
        for (OrderItem item : order.items()) {
            if (store.ownsProduct(order.buyerSub(), item.productId())) {
                throw new OrderException(Reason.PRODUCT_ALREADY_OWNED, "Ya posees uno de los productos");
            }
        }
        store.insert(order, key, hash);
    }
}
