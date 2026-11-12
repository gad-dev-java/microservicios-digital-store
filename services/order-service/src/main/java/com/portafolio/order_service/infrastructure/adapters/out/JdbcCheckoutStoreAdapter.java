package com.portafolio.order_service.infrastructure.adapters.out;

import com.portafolio.order_service.application.ports.out.CheckoutStorePort;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.*;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JdbcCheckoutStoreAdapter implements CheckoutStorePort {
    private final JdbcTemplate jdbc;

    @Transactional
    @Override
    public Start reserve(UUID orderId, String buyerSub) {
        List<CheckoutOrderRow> orders = jdbc.query(
                """
                SELECT id, buyer_email, total_amount, currency, status
                FROM orders
                WHERE id = ? AND buyer_sub = ?
                FOR UPDATE
                """,
                (rs, rowNum) -> new CheckoutOrderRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("buyer_email"),
                        rs.getBigDecimal("total_amount"),
                        rs.getString("currency"),
                        Status.valueOf(rs.getString("status"))),
                orderId,
                buyerSub);

        if (orders.isEmpty()) {
            throw new OrderException(Reason.ORDER_NOT_FOUND, "Pedido no encontrado");
        }

        CheckoutOrderRow order = orders.getFirst();
        if (!order.status().allowsCheckout()) {
            throw new OrderException(Reason.ORDER_NOT_PAYABLE, "El pedido no permite otro pago");
        }

        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM order_items item
                    JOIN entitlements entitlement
                      ON entitlement.product_id = item.product_id
                     AND entitlement.buyer_sub = ?
                    WHERE item.order_id = ?
                )
                """, Boolean.class, buyerSub, orderId);

        if (Boolean.TRUE.equals(owned)) {
            throw new OrderException(Reason.PRODUCT_ALREADY_OWNED, "Ya posees uno de los productos del pedido");
        }

        List<ActiveAttempt> active = jdbc.query(
                """
                SELECT id, status, checkout_url
                FROM payment_attempts
                WHERE order_id = ?
                  AND status IN ('CREATING', 'PENDING', 'REVIEW_REQUIRED')
                LIMIT 1
                """,
                (rs, rowNum) -> new ActiveAttempt(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("checkout_url")),
                orderId);

        if (!active.isEmpty()) {
            ActiveAttempt previous = active.getFirst();
            if ("PENDING".equals(previous.status())
                    && previous.checkoutUrl() != null
                    && !previous.checkoutUrl().isBlank()) {
                return new Start(
                        new Attempt(
                                previous.id(),
                                orderId,
                                null,
                                order.amount(),
                                order.currency(),
                                order.buyerEmail(),
                                List.of()),
                        previous.checkoutUrl());
            }
            throw new OrderException(
                    Reason.PAYMENT_IN_PROGRESS, "El intento de pago está en curso o requiere revisión");
        }

        List<OrderItem> items = jdbc.query(
                """
                SELECT id, product_id, sku, title, unit_price
                FROM order_items
                WHERE order_id = ?
                ORDER BY product_id
                """,
                (rs, rowNum) -> new OrderItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("sku"),
                        rs.getString("title"),
                        rs.getBigDecimal("unit_price")),
                orderId);

        UUID attemptId = UUID.randomUUID();
        UUID providerKey = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO payment_attempts
                    (id, order_id, provider_idempotency_key,
                     amount, currency, status)
                VALUES (?, ?, ?, ?, ?, 'CREATING')
                """, attemptId, orderId, providerKey, order.amount(), order.currency());

        return new Start(
                new Attempt(
                        attemptId, orderId, providerKey, order.amount(), order.currency(), order.buyerEmail(), items),
                null);
    }

    @Transactional
    @Override
    public void complete(UUID attemptId, String providerOrderId, String checkoutUrl) {
        int updatedAttempt = jdbc.update("""
                UPDATE payment_attempts
                SET provider_order_id = ?,
                    checkout_url = ?,
                    provider_status = 'created',
                    status = 'PENDING'
                WHERE id = ? AND status = 'CREATING'
                """, providerOrderId, checkoutUrl, attemptId);

        if (updatedAttempt != 1) {
            throw new OrderException(Reason.PAYMENT_IN_PROGRESS, "No se pudo finalizar el intento de pago");
        }

        int updatedOrder = jdbc.update("""
                UPDATE orders
                SET status = 'PENDING_PAYMENT'
                WHERE id = (
                    SELECT order_id FROM payment_attempts WHERE id = ?
                )
                  AND status IN ('CREATED', 'PENDING_PAYMENT', 'PAYMENT_FAILED')
                """, attemptId);

        if (updatedOrder != 1) {
            throw new OrderException(Reason.ORDER_NOT_PAYABLE, "El pedido cambió de estado durante el checkout");
        }
    }
}
