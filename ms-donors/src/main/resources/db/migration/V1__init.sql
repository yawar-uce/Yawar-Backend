-- ms-donors initial schema (YAW-DOC-07 §7.3.1). outbox and inbox come from yawar-platform (V0_1).
-- Personal data stays in this database only; events carry IDs (LOPDP).

CREATE TABLE donor (
    id                 UUID PRIMARY KEY,
    user_id            UUID        NOT NULL,           -- Keycloak subject (sub): the only link to the identity
    blood_type         BYTEA,                          -- health data: AES-GCM ciphertext, never plain text
    blood_type_key_id  TEXT,                           -- key that encrypted blood_type (allows key rotation)
    birth_date         DATE,
    weight             NUMERIC(5, 2) CHECK (weight > 0),
    preferred_point_id UUID,                           -- donation point of ms-appointments (no cross-service FK)
    profile_complete   BOOLEAN     NOT NULL DEFAULT false,
    version            BIGINT      NOT NULL DEFAULT 0, -- optimistic locking: ETag / If-Match → 412
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One donor per account, also when two first requests (lazy profile creation) arrive at the same time.
    CONSTRAINT uq_donor_user UNIQUE (user_id)
);

CREATE TABLE consent (
    id             UUID PRIMARY KEY,
    donor_id       UUID        NOT NULL REFERENCES donor (id) ON DELETE RESTRICT, -- deletion = anonymization
    purpose        TEXT        NOT NULL,               -- e.g. DATA_PROCESSING, CAMPAIGNS
    policy_version TEXT        NOT NULL,               -- version of the text the donor accepted
    granted_at     TIMESTAMPTZ NOT NULL,
    revoked_at     TIMESTAMPTZ,
    CONSTRAINT ck_consent_revoked_after_granted CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);

-- At most one active consent per purpose; revoked ones stay as evidence (LOPDP).
CREATE UNIQUE INDEX uq_consent_active ON consent (donor_id, purpose) WHERE revoked_at IS NULL;

-- Local projection of DonationRecorded (ms-appointments), used for the eligibility calculation.
CREATE TABLE last_donation (
    donor_id          UUID   PRIMARY KEY REFERENCES donor (id) ON DELETE RESTRICT,
    donation_date     DATE   NOT NULL,
    aggregate_version BIGINT NOT NULL                  -- last applied event version: older events are ignored
);
