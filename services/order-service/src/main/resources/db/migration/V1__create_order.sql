CREATE FUNCTION set_updated_at()
    RETURNS trigger
    LANGUAGE plpgsql
AS
$$
BEGIN
    NEW.updated_at := statement_timestamp();
    RETURN NEW;
END;
$$;

-- Estados: CREATED -> PENDING_PAYMENT -> PAID | PAYMENT_FAILED;
-- PAYMENT_FAILED -> PENDING_PAYMENT al reintentar; PAID es terminal.
CREATE TABLE orders
(
    id           UUID PRIMARY KEY,
    buyer_sub    VARCHAR(255)   NOT NULL,
    buyer_email  VARCHAR(320)   NOT NULL,
    total_amount NUMERIC(12, 2) NOT NULL CHECK (total_amount > 0),
    currency     VARCHAR(3)     NOT NULL DEFAULT 'PEN' CHECK (currency = 'PEN'),
    status       VARCHAR(24)    NOT NULL DEFAULT 'CREATED'
        CHECK (status IN ('CREATED', 'PENDING_PAYMENT', 'PAID', 'PAYMENT_FAILED')),
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    paid_at      TIMESTAMPTZ,
    CHECK ((status = 'PAID') = (paid_at IS NOT NULL))
);

CREATE INDEX orders_buyer_created_idx ON orders (buyer_sub, created_at DESC);

CREATE TRIGGER orders_set_updated_at
    BEFORE UPDATE
    ON orders
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TABLE order_items
(
    id         UUID PRIMARY KEY,
    order_id   UUID           NOT NULL REFERENCES orders (id),
    product_id UUID           NOT NULL,
    sku        VARCHAR(64)    NOT NULL,
    title      VARCHAR(200)   NOT NULL,
    unit_price NUMERIC(12, 2) NOT NULL CHECK (unit_price > 0),
    quantity   INTEGER        NOT NULL DEFAULT 1 CHECK (quantity = 1),
    line_total NUMERIC(12, 2) NOT NULL,
    UNIQUE (order_id, product_id),
    CHECK (line_total = unit_price * quantity)
);

CREATE INDEX order_items_order_idx ON order_items (order_id);

CREATE TABLE idempotency_requests
(
    id              UUID PRIMARY KEY,
    actor_sub       VARCHAR(255) NOT NULL,
    operation       VARCHAR(40)  NOT NULL,
    idempotency_key VARCHAR(160) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    order_id        UUID         NOT NULL REFERENCES orders (id),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ  NOT NULL DEFAULT (now() + INTERVAL '30 days'),
    UNIQUE (actor_sub, operation, idempotency_key),
    CHECK (expires_at > created_at)
);

CREATE INDEX idempotency_requests_expires_idx ON idempotency_requests (expires_at);

-- Invocar diariamente desde @Scheduled en Order Service hasta que retorne 0.
-- SKIP LOCKED permite ejecutar otra instancia sin reclamar las mismas filas.
CREATE FUNCTION purge_expired_idempotency_requests(batch_size INTEGER)
    RETURNS INTEGER
    LANGUAGE plpgsql
AS
$$
DECLARE
    deleted_count INTEGER;
BEGIN
    IF batch_size IS NULL OR batch_size < 1 OR batch_size > 10000 THEN
        RAISE EXCEPTION 'batch_size must be between 1 and 10000';
    END IF;

    WITH expired AS (SELECT id
                     FROM idempotency_requests
                     WHERE expires_at < now()
                     ORDER BY expires_at
                     LIMIT batch_size FOR UPDATE SKIP LOCKED)
    DELETE
    FROM idempotency_requests AS requests
        USING expired
    WHERE requests.id = expired.id;

    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;

-- Intento activo = CREATING, PENDING o REVIEW_REQUIRED (maximo uno por pedido).
-- provider_idempotency_key se conserva al reintentar la creacion de un mismo intento.
-- external_reference en Mercado Pago = payment_attempts.id.
-- review_reason (texto libre acotado): UNRESOLVED_TIMEOUT, DUPLICATE_PAYMENT,
-- DUPLICATE_PURCHASE,
-- AMOUNT_MISMATCH, CURRENCY_MISMATCH, REFERENCE_MISMATCH, LIVE_MODE_MISMATCH.
CREATE TABLE payment_attempts
(
    id                       UUID PRIMARY KEY,
    order_id                 UUID           NOT NULL REFERENCES orders (id),
    provider_order_id        VARCHAR(128) UNIQUE,
    provider_idempotency_key UUID           NOT NULL UNIQUE,
    checkout_url             TEXT,
    amount                   NUMERIC(12, 2) NOT NULL CHECK (amount > 0),
    currency                 VARCHAR(3)     NOT NULL DEFAULT 'PEN' CHECK (currency = 'PEN'),
    status                   VARCHAR(20)    NOT NULL DEFAULT 'CREATING'
        CHECK (status IN ('CREATING', 'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'ERROR', 'REVIEW_REQUIRED')),
    provider_status          VARCHAR(60),
    provider_status_detail   VARCHAR(100),
    review_reason            VARCHAR(100),
    resolved_by              VARCHAR(255),
    created_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    resolved_at              TIMESTAMPTZ
);

CREATE UNIQUE INDEX payment_attempts_one_active_idx ON payment_attempts (order_id)
    WHERE status IN ('CREATING', 'PENDING', 'REVIEW_REQUIRED');
CREATE INDEX payment_attempts_order_idx ON payment_attempts (order_id, created_at DESC);
CREATE INDEX payment_attempts_reconcile_idx ON payment_attempts (created_at)
    WHERE status IN ('CREATING', 'PENDING');
CREATE INDEX payment_attempts_review_idx ON payment_attempts (created_at)
    WHERE status = 'REVIEW_REQUIRED' OR review_reason IS NOT NULL;

CREATE TRIGGER payment_attempts_set_updated_at
    BEFORE UPDATE
    ON payment_attempts
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

-- Retencion: filas PROCESSED con mas de 30 dias (por processed_at), purga por lotes.
CREATE TABLE webhook_receipts
(
    id                       UUID PRIMARY KEY,
    provider_notification_id VARCHAR(128) NOT NULL UNIQUE,
    provider_order_id        VARCHAR(128),
    action                   VARCHAR(80),
    payload                  JSONB        NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    status                   VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED'
        CHECK (status IN ('RECEIVED', 'PROCESSED', 'FAILED')),
    last_error               TEXT,
    received_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at             TIMESTAMPTZ
);

CREATE INDEX webhook_receipts_provider_order_idx ON webhook_receipts (provider_order_id);
CREATE INDEX webhook_receipts_purge_idx ON webhook_receipts (processed_at)
    WHERE status = 'PROCESSED';

-- Fuente de verdad para autorizar descargas.
-- Insertar con INSERT ... ON CONFLICT DO NOTHING (sin columna objetivo): el pedido
-- pasa a PAID aunque el comprador ya posea el producto.
-- La clave compuesta garantiza que solo se concede un producto incluido en el pedido.
CREATE TABLE entitlements
(
    id         UUID PRIMARY KEY,
    buyer_sub  VARCHAR(255) NOT NULL,
    product_id UUID         NOT NULL,
    order_id   UUID         NOT NULL,
    granted_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (buyer_sub, product_id),
    UNIQUE (order_id, product_id),
    FOREIGN KEY (order_id, product_id) REFERENCES order_items (order_id, product_id)
);

CREATE INDEX entitlements_buyer_idx ON entitlements (buyer_sub);

-- Solo se publica ORDER_PAID. El id de la fila es el eventId del mensaje.
-- UNIQUE (order_id, event_type) evita duplicar el evento de un pedido; no garantiza
-- entrega exactamente una vez en Kafka.
-- DEAD: se alcanzo el maximo de intentos (configurable); requiere revision.
-- Retencion: filas PUBLISHED con mas de 30 dias (por published_at), purga por lotes.
CREATE TABLE outbox_events
(
    id              UUID PRIMARY KEY,
    order_id        UUID        NOT NULL REFERENCES orders (id),
    event_type      VARCHAR(40) NOT NULL CHECK (event_type = 'ORDER_PAID'),
    payload         JSONB       NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    traceparent     VARCHAR(128),
    tracestate      VARCHAR(512),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'PUBLISHED', 'FAILED', 'DEAD')),
    attempts        INTEGER     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_until     TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    UNIQUE (order_id, event_type)
);

CREATE INDEX outbox_events_due_idx ON outbox_events (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX outbox_events_lease_idx ON outbox_events (lease_until)
    WHERE status = 'IN_FLIGHT';
CREATE INDEX outbox_events_purge_idx ON outbox_events (published_at)
    WHERE status = 'PUBLISHED';

-- Relay local con un solo trabajador: reclama con SELECT ... FOR UPDATE SKIP LOCKED,
-- publica con Kafka key = order_id y confirma PUBLISHED. Un lease vencido permite
-- reintento. Una caida tras publicar y antes de confirmar puede producir un
-- duplicado; source_message_id en email_deliveries permite deduplicar el consumo.