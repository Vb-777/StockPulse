-- StockPulse schema. Idempotent: safe to run on every start.

CREATE TABLE IF NOT EXISTS products (
    sku          TEXT PRIMARY KEY CHECK (sku ~ '^[A-Z0-9][A-Z0-9-]{1,31}$'),
    name         TEXT NOT NULL,
    price_cents  BIGINT NOT NULL CHECK (price_cents >= 0),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Stock is split into three buckets. Every unit is in exactly one of them, so
-- available + reserved + sold never changes except by an explicit restock.
CREATE TABLE IF NOT EXISTS inventory (
    sku        TEXT PRIMARY KEY REFERENCES products(sku),
    available  BIGINT NOT NULL CHECK (available >= 0),
    reserved   BIGINT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    sold       BIGINT NOT NULL DEFAULT 0 CHECK (sold >= 0),
    total      BIGINT NOT NULL,
    CONSTRAINT units_conserved CHECK (available + reserved + sold = total)
);

CREATE TABLE IF NOT EXISTS orders (
    id           UUID PRIMARY KEY,
    client_id    TEXT NOT NULL,
    status       TEXT NOT NULL CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    total_cents  BIGINT NOT NULL,
    reason       TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS orders_status_idx ON orders (status) WHERE status = 'PENDING';

CREATE TABLE IF NOT EXISTS order_lines (
    order_id     UUID NOT NULL REFERENCES orders(id),
    sku          TEXT NOT NULL REFERENCES products(sku),
    qty          INT NOT NULL CHECK (qty > 0),
    price_cents  BIGINT NOT NULL,
    PRIMARY KEY (order_id, sku)
);

-- One row per (client, Idempotency-Key). The stored response is replayed verbatim on retry.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    client_id      TEXT NOT NULL,
    idem_key       TEXT NOT NULL,
    request_hash   TEXT NOT NULL,
    status_code    INT,
    response_body  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (client_id, idem_key)
);

-- Transactional outbox: written in the same transaction as the order, relayed to the queue later.
CREATE TABLE IF NOT EXISTS outbox (
    id            BIGSERIAL PRIMARY KEY,
    order_id      UUID NOT NULL,
    event_type    TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS outbox_unpublished_idx ON outbox (id) WHERE published_at IS NULL;

-- One row per state change actually applied: lets tests prove each order was processed exactly once.
CREATE TABLE IF NOT EXISTS order_transitions (
    order_id    UUID NOT NULL REFERENCES orders(id),
    from_status TEXT NOT NULL,
    to_status   TEXT NOT NULL,
    worker      TEXT NOT NULL,
    at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (order_id, from_status)
);
