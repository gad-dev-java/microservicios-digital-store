UPDATE outbox_events AS event
SET payload = event.payload || jsonb_build_object(
        'schemaVersion', 1,
        'occurredAt', purchase.paid_at,
        'amount', purchase.total_amount,
        'currency', purchase.currency,
        'items', (
            SELECT COALESCE(
                           jsonb_agg(
                                   jsonb_build_object(
                                           'productId', item.product_id::text,
                                           'sku', item.sku,
                                           'title', item.title,
                                           'quantity', item.quantity,
                                           'unitPrice', item.unit_price
                                   ) ORDER BY item.id),
                           '[]'::jsonb)
            FROM order_items AS item
            WHERE item.order_id = event.order_id
        )
                               )
FROM orders AS purchase
WHERE purchase.id = event.order_id
  AND event.event_type = 'ORDER_PAID'
  AND NOT (event.payload ? 'schemaVersion');