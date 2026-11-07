CREATE TABLE products
(
    id              UUID PRIMARY KEY,
    sku             VARCHAR(64)    NOT NULL UNIQUE,
    title           VARCHAR(200)   NOT NULL,
    description     TEXT           NOT NULL,
    price           NUMERIC(12, 2) NOT NULL CHECK (price > 0),
    currency        VARCHAR(3)     NOT NULL DEFAULT 'PEN' CHECK (currency = 'PEN'),
    -- Los metadatos del archivo son nulos mientras el producto es borrador.
    -- storage_key es una clave relativa generada con UUID, nunca una ruta absoluta.
    storage_key     VARCHAR(255),
    file_name       VARCHAR(255),
    content_type    VARCHAR(120),
    file_size       BIGINT CHECK (file_size > 0),
    checksum_sha256 VARCHAR(64) CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    status          VARCHAR(20)    NOT NULL DEFAULT 'DRAFT'
        CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CHECK (btrim(sku) <> ''),
    CHECK (btrim(title) <> ''),
    CHECK (btrim(file_name) <> ''),
    CHECK (storage_key ~ '^[A-Za-z0-9][A-Za-z0-9._/-]*$' AND storage_key !~ '\.\.'),
    -- Publicar exige el archivo completo.
    CHECK (
        status <> 'PUBLISHED'
            OR (storage_key IS NOT NULL
            AND file_name IS NOT NULL
            AND content_type IS NOT NULL
            AND file_size IS NOT NULL
            AND checksum_sha256 IS NOT NULL)
        )
);

CREATE INDEX products_public_idx ON products (title, id)
    WHERE status = 'PUBLISHED';

-- El trigger cubre tanto actualizaciones JPA como SQL directo.
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

CREATE TRIGGER products_set_updated_at
    BEFORE UPDATE
    ON products
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();