-- Admission identity is account-scoped. The command inbox/result use world-scoped request IDs,
-- so the public UUID must never be used directly as their key.
CREATE TABLE general_creation_receipt (
    world_id                   INTEGER     NOT NULL REFERENCES world_state(id),
    account_id                 BIGINT      NOT NULL,
    client_request_id          UUID        NOT NULL,
    internal_command_request_id TEXT      NOT NULL,
    body_sha256                CHAR(64)    NOT NULL,
    choice_kind                VARCHAR(16) NOT NULL,
    admission_status           VARCHAR(16) NOT NULL DEFAULT 'ACCEPTED',
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT general_creation_receipt_pk PRIMARY KEY (world_id, account_id, client_request_id),
    CONSTRAINT general_creation_receipt_command_uq UNIQUE (world_id, internal_command_request_id),
    CONSTRAINT general_creation_receipt_account_ck CHECK (account_id > 0),
    CONSTRAINT general_creation_receipt_command_ck CHECK (internal_command_request_id ~ '^creation:[0-9a-f]{64}$'),
    CONSTRAINT general_creation_receipt_hash_ck CHECK (body_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT general_creation_receipt_kind_ck CHECK (choice_kind IN ('CUSTOM', 'HISTORICAL')),
    CONSTRAINT general_creation_receipt_status_ck CHECK (admission_status = 'ACCEPTED')
);

-- Only new CUSTOM rows carry this writer-computed key. Existing scenario/legacy names are
-- checked by the daemon from the live world; this index does not rewrite or backfill them.
-- A second writer cannot commit two created generals with the same canonical key.
CREATE UNIQUE INDEX general_creation_name_key_v1_uq
    ON general (world_id, (meta ->> 'creationNameKeyV1'))
    WHERE meta ? 'creationNameKeyV1';
