-- source_message_id = eventId del mensaje ORDER_PAID (clave de deduplicacion / inbox).
-- Insertar con ON CONFLICT DO NOTHING y terminar el consumo con exito si ya existe.
-- DEAD: se alcanzo el maximo de intentos de envio (configurable); requiere revision.
-- Retencion: filas SENT con mas de 60 dias (por sent_at), purga por lotes.
CREATE TABLE email_deliveries
(
    source_message_id UUID PRIMARY KEY,
    order_id          UUID         NOT NULL,
    notification_type VARCHAR(40)  NOT NULL CHECK (notification_type = 'ORDER_PAID'),
    recipient_email   VARCHAR(320) NOT NULL,
    subject           VARCHAR(255) NOT NULL,
    body              TEXT         NOT NULL,
    traceparent       VARCHAR(128),
    tracestate        VARCHAR(512),
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SENT', 'FAILED', 'DEAD')),
    attempts          INTEGER      NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    lease_until       TIMESTAMPTZ,
    last_error        TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at           TIMESTAMPTZ,
    UNIQUE (order_id, notification_type)
);

CREATE INDEX email_deliveries_due_idx ON email_deliveries (next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'FAILED');
CREATE INDEX email_deliveries_lease_idx ON email_deliveries (lease_until)
    WHERE status = 'IN_FLIGHT';
CREATE INDEX email_deliveries_purge_idx ON email_deliveries (sent_at)
    WHERE status = 'SENT';

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

CREATE TRIGGER email_deliveries_set_updated_at
    BEFORE UPDATE
    ON email_deliveries
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();