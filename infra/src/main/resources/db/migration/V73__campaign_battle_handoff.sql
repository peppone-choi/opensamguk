-- Campaign-owned immutable handoff and per-entity battle locks. V70, V71, V72 precede V73.
-- The campaign flush inserts handoff and locks in its existing world transaction.
-- Battle-owned ticket/session creation and rejection happen only after that transaction commits.

CREATE TABLE campaign_battle_handoff (
    world_id             INTEGER       NOT NULL REFERENCES world_state(id),
    battle_id            VARCHAR(128)  NOT NULL,
    cause_event_id       VARCHAR(128)  NOT NULL,
    payload_text         TEXT          NOT NULL,
    payload_sha256       VARCHAR(64)   NOT NULL,
    rule_sha256          VARCHAR(64)   NOT NULL,
    catalog_sha256       VARCHAR(64)   NOT NULL,
    terrain_sha256       VARCHAR(64)   NOT NULL,
    seed                 BIGINT        NOT NULL,
    lock_generation      BIGINT        NOT NULL,
    lock_set_revision    BIGINT        NOT NULL,
    join_deadline_at     TIMESTAMPTZ   NOT NULL,
    deadline_at          TIMESTAMPTZ   NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT campaign_battle_handoff_pk PRIMARY KEY (world_id, battle_id),
    CONSTRAINT campaign_battle_handoff_cause_uq UNIQUE (world_id, cause_event_id),
    CONSTRAINT campaign_battle_handoff_payload_ck CHECK (jsonb_typeof(payload_text::jsonb) = 'object'),
    CONSTRAINT campaign_battle_handoff_sha_ck CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$' AND rule_sha256 ~ '^[0-9a-f]{64}$'
        AND catalog_sha256 ~ '^[0-9a-f]{64}$' AND terrain_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT campaign_battle_handoff_lock_ck CHECK (lock_generation > 0 AND lock_set_revision > 0),
    CONSTRAINT campaign_battle_handoff_deadline_ck CHECK (join_deadline_at < deadline_at),
    CONSTRAINT campaign_battle_handoff_cause_ck CHECK (length(btrim(cause_event_id)) > 0)
);
CREATE INDEX campaign_battle_handoff_scan_idx
    ON campaign_battle_handoff (world_id, created_at, battle_id);

CREATE TABLE campaign_battle_lock (
    world_id             INTEGER       NOT NULL,
    battle_id            VARCHAR(128)  NOT NULL,
    entity_key           VARCHAR(160)  NOT NULL,
    expected_revision    BIGINT        NOT NULL,
    lock_generation      BIGINT        NOT NULL,
    lock_set_revision    BIGINT        NOT NULL,
    released_at          TIMESTAMPTZ   NULL,
    CONSTRAINT campaign_battle_lock_pk PRIMARY KEY (world_id, battle_id, entity_key),
    CONSTRAINT campaign_battle_lock_handoff_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES campaign_battle_handoff (world_id, battle_id),
    CONSTRAINT campaign_battle_lock_entity_ck CHECK (length(btrim(entity_key)) > 0),
    CONSTRAINT campaign_battle_lock_revision_ck CHECK (
        expected_revision >= 0 AND lock_generation > 0 AND lock_set_revision > 0)
);
CREATE UNIQUE INDEX campaign_battle_lock_active_entity_uq
    ON campaign_battle_lock (world_id, entity_key) WHERE released_at IS NULL;
CREATE INDEX campaign_battle_lock_battle_active_idx
    ON campaign_battle_lock (world_id, battle_id) WHERE released_at IS NULL;

-- A malformed committed handoff is durable evidence; it must not create a battle session.
CREATE TABLE battle_handoff_rejection (
    world_id             INTEGER       NOT NULL,
    battle_id            VARCHAR(128)  NOT NULL,
    handoff_sha256       VARCHAR(64)   NOT NULL,
    reason_code          VARCHAR(64)   NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT battle_handoff_rejection_pk PRIMARY KEY (world_id, battle_id),
    CONSTRAINT battle_handoff_rejection_handoff_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES campaign_battle_handoff (world_id, battle_id),
    CONSTRAINT battle_handoff_rejection_sha_ck CHECK (handoff_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT battle_handoff_rejection_reason_ck CHECK (length(btrim(reason_code)) > 0)
);
