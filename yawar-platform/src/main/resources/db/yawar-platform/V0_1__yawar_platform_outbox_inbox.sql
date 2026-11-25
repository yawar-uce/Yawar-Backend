-- yawar-platform: transactional outbox and idempotent inbox (YAW-DOC-07 §9.2, ADR-026).
-- Services add "classpath:db/yawar-platform" to spring.flyway.locations; their own migrations start at V1.

CREATE TABLE outbox (
    id                UUID PRIMARY KEY,
    aggregate_type    TEXT        NOT NULL,
    aggregate_id      TEXT        NOT NULL,
    aggregate_version BIGINT      NOT NULL,
    event_type        TEXT        NOT NULL,
    exchange          TEXT        NOT NULL,
    routing_key       TEXT        NOT NULL,
    schema_version    INT         NOT NULL DEFAULT 1,
    payload           JSONB       NOT NULL,
    trace_context     JSONB       NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    attempts          INT         NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error        TEXT
);

-- Relay: pending rows in order (partial index, stays small).
CREATE INDEX ix_outbox_pending ON outbox (created_at) WHERE published_at IS NULL;
-- Housekeeping: published rows by age.
CREATE INDEX ix_outbox_published ON outbox (published_at) WHERE published_at IS NOT NULL;

CREATE TABLE inbox (
    event_id    UUID        NOT NULL,
    consumer    TEXT        NOT NULL,
    event_type  TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX ix_inbox_received ON inbox (received_at);
