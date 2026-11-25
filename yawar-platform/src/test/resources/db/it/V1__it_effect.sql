-- Effect table of the integration-test consumer (written in the same transaction as the inbox row).
CREATE TABLE it_effect (
    event_id UUID NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
