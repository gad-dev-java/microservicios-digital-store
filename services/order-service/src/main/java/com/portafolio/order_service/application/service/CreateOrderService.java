package com.portafolio.order_service.application.service;

import com.portafolio.order_service.application.ports.in.Command;
import com.portafolio.order_service.application.ports.in.CreateOrderUseCase;
import com.portafolio.order_service.application.ports.in.Result;
import com.portafolio.order_service.application.ports.out.CatalogPort;
import com.portafolio.order_service.application.ports.out.OrderStorePort;
import com.portafolio.order_service.application.ports.out.StoredOrder;
import com.portafolio.order_service.domain.exceptions.OrderException;
import com.portafolio.order_service.domain.models.Order;
import com.portafolio.order_service.domain.models.OrderItem;
import com.portafolio.order_service.domain.models.Reason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class CreateOrderService implements CreateOrderUseCase {
    private final CatalogPort catalog;
    private final OrderStorePort store;
    private final PersistOrderService persister;

    @Override
    public Result create(Command command) {
        List<UUID> ids = command.productIds();
        String key = command.idempotencyKey();

        if (ids == null
                || ids.isEmpty()
                || ids.size() > 10
                || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()
                || key == null
                || key.isBlank()
                || key.length() > 160) {
            throw new OrderException(
                    Reason.INVALID_ORDER_REQUEST, "Se requiere una clave y entre 1 y 10 productos distintos");
        }

        List<UUID> canonicalIds = ids.stream().sorted().toList();
        String hash = hash(canonicalIds);

        var existing = store.findByIdempotency(command.buyerSub(), key);
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }

        for (UUID id : canonicalIds) {
            if (store.ownsProduct(command.buyerSub(), id)) {
                throw new OrderException(Reason.PRODUCT_ALREADY_OWNED, "Ya posees uno de los productos");
            }
        }

        List<OrderItem> items = canonicalIds.stream()
                .map(catalog::getPublished)
                .map(product ->
                        new OrderItem(UUID.randomUUID(), product.id(), product.sku(), product.title(), product.price()))
                .toList();

        Order order = Order.create(command.buyerSub(), command.buyerEmail(), items);

        try {
            persister.persist(order, key, hash);
            log.info("Pedido creado: orderId={}", order.id());
            return new Result(order, false);
        } catch (OrderException exception) {
            if (exception.reason() != Reason.IDEMPOTENCY_RACE) {
                throw exception;
            }

            StoredOrder winner = store.findByIdempotency(command.buyerSub(), key)
                    .orElseThrow(() -> new IllegalStateException("No se encontró el pedido ganador"));
            return replay(winner, hash);
        }
    }

    private Result replay(StoredOrder stored, String hash) {
        if (!stored.requestHash().equals(hash)) {
            throw new OrderException(Reason.IDEMPOTENCY_CONFLICT, "La clave ya se usó con otros productos");
        }
        return new Result(stored.order(), true);
    }

    private String hash(List<UUID> ids) {
        String canonical = ids.stream().map(UUID::toString).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
