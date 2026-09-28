-- Waryong realtime battle-owned persistence. Campaign deltas remain owned by the engine flush path.
-- Source JSON is stored as TEXT so SHA-256 pins cover the exact committed bytes, not jsonb normalization.
-- V67 world-state catch-up must land before this migration is made ready for main.

CREATE TABLE battle_ticket (
    world_id            INTEGER       NOT NULL REFERENCES world_state(id),
    battle_id           VARCHAR(128)  NOT NULL,
    payload_text        TEXT          NOT NULL,
    payload_sha256      VARCHAR(64)   NOT NULL,
    rule_sha256         VARCHAR(64)   NOT NULL,
    catalog_sha256      VARCHAR(64)   NOT NULL,
    terrain_sha256      VARCHAR(64)   NOT NULL,
    seed                BIGINT        NOT NULL,
    lock_generation     BIGINT        NOT NULL,
    lock_set_revision   BIGINT        NOT NULL,
    join_deadline_at    TIMESTAMPTZ   NOT NULL,
    deadline_at         TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT battle_ticket_pk PRIMARY KEY (world_id, battle_id),
    CONSTRAINT battle_ticket_payload_ck CHECK (jsonb_typeof(payload_text::jsonb) = 'object'),
    CONSTRAINT battle_ticket_sha_ck CHECK (
        payload_sha256 ~ '^[0-9a-f]{64}$' AND rule_sha256 ~ '^[0-9a-f]{64}$'
        AND catalog_sha256 ~ '^[0-9a-f]{64}$' AND terrain_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT battle_ticket_lock_ck CHECK (lock_generation >= 0 AND lock_set_revision >= 0),
    CONSTRAINT battle_ticket_deadline_ck CHECK (join_deadline_at < deadline_at)
);

CREATE TABLE battle_participant (
    world_id             INTEGER       NOT NULL,
    battle_id            VARCHAR(128)  NOT NULL,
    participant_id       INTEGER       NOT NULL,
    account_id           INTEGER       NOT NULL,
    general_id           INTEGER       NOT NULL,
    side                 VARCHAR(8)    NOT NULL,
    authority_revision   BIGINT        NOT NULL,
    CONSTRAINT battle_participant_pk PRIMARY KEY (world_id, battle_id, participant_id),
    CONSTRAINT battle_participant_world_fk FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_participant_ticket_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES battle_ticket(world_id, battle_id),
    CONSTRAINT battle_participant_account_uq UNIQUE (world_id, battle_id, account_id),
    CONSTRAINT battle_participant_identity_ck CHECK (
        participant_id > 0 AND account_id > 0 AND general_id > 0 AND authority_revision >= 0),
    CONSTRAINT battle_participant_side_ck CHECK (side IN ('ATTACKER', 'DEFENDER'))
);

CREATE TABLE battle_session (
    world_id             INTEGER       NOT NULL,
    battle_id            VARCHAR(128)  NOT NULL,
    phase                VARCHAR(24)   NOT NULL,
    session_epoch        BIGINT        NOT NULL DEFAULT 0,
    lease_owner          VARCHAR(128)  NULL,
    lease_until          TIMESTAMPTZ   NULL,
    current_tick         INTEGER       NOT NULL DEFAULT 0,
    latest_event_seq     BIGINT        NOT NULL DEFAULT 0,
    latest_snapshot_seq  BIGINT        NOT NULL DEFAULT 0,
    join_deadline_at     TIMESTAMPTZ   NOT NULL,
    deadline_at          TIMESTAMPTZ   NOT NULL,
    resolved_at          TIMESTAMPTZ   NULL,
    CONSTRAINT battle_session_pk PRIMARY KEY (world_id, battle_id),
    CONSTRAINT battle_session_world_fk FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_session_ticket_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES battle_ticket(world_id, battle_id),
    CONSTRAINT battle_session_phase_ck CHECK (phase IN (
        'READY', 'JOINING', 'RUNNING', 'RESOLVING', 'RESULT_PENDING', 'APPLIED',
        'RESULT_BLOCKED', 'QUARANTINED')),
    CONSTRAINT battle_session_count_ck CHECK (
        session_epoch >= 0 AND current_tick BETWEEN 0 AND 3000
        AND latest_event_seq >= 0 AND latest_snapshot_seq >= 0),
    CONSTRAINT battle_session_lease_ck CHECK ((lease_owner IS NULL) = (lease_until IS NULL)),
    CONSTRAINT battle_session_deadline_ck CHECK (join_deadline_at < deadline_at)
);
CREATE INDEX battle_session_phase_idx ON battle_session (world_id, phase, deadline_at, battle_id);

CREATE TABLE battle_event (
    world_id         INTEGER       NOT NULL,
    battle_id        VARCHAR(128)  NOT NULL,
    event_seq        BIGINT        NOT NULL,
    session_epoch    BIGINT        NOT NULL,
    accepted_tick    INTEGER       NOT NULL,
    effective_tick   INTEGER       NOT NULL,
    event_type       VARCHAR(64)   NOT NULL,
    participant_id   INTEGER       NULL,
    transition_id    VARCHAR(128)  NULL,
    payload_text     TEXT          NOT NULL,
    payload_sha256   VARCHAR(64)   NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT battle_event_pk PRIMARY KEY (world_id, battle_id, event_seq),
    CONSTRAINT battle_event_world_fk FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_event_ticket_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES battle_ticket(world_id, battle_id),
    CONSTRAINT battle_event_count_ck CHECK (
        event_seq > 0 AND session_epoch > 0 AND accepted_tick BETWEEN 0 AND 3000
        AND effective_tick BETWEEN accepted_tick AND 3000),
    CONSTRAINT battle_event_payload_ck CHECK (
        jsonb_typeof(payload_text::jsonb) = 'object' AND payload_sha256 ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX battle_event_transition_uq ON battle_event (world_id, battle_id, transition_id)
    WHERE transition_id IS NOT NULL;

CREATE TABLE battle_command_receipt (
    world_id             INTEGER       NOT NULL,
    battle_id            VARCHAR(128)  NOT NULL,
    participant_id       INTEGER       NOT NULL,
    client_command_id    VARCHAR(128)  NOT NULL,
    intent_sha256        VARCHAR(64)   NOT NULL,
    verdict              VARCHAR(8)    NOT NULL,
    reason_code          VARCHAR(64)   NULL,
    server_tick          INTEGER       NOT NULL,
    effective_tick       INTEGER       NULL,
    event_seq            BIGINT        NULL,
    authority_revision   BIGINT        NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT battle_command_receipt_pk PRIMARY KEY
        (world_id, battle_id, participant_id, client_command_id),
    CONSTRAINT battle_command_receipt_world_fk FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_command_receipt_participant_fk FOREIGN KEY (world_id, battle_id, participant_id)
        REFERENCES battle_participant(world_id, battle_id, participant_id),
    CONSTRAINT battle_command_receipt_event_fk FOREIGN KEY (world_id, battle_id, event_seq)
        REFERENCES battle_event(world_id, battle_id, event_seq),
    CONSTRAINT battle_command_receipt_sha_ck CHECK (intent_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT battle_command_receipt_verdict_ck CHECK (
        (verdict = 'ACCEPTED' AND reason_code IS NULL AND effective_tick IS NOT NULL AND event_seq IS NOT NULL)
        OR (verdict = 'REJECTED' AND reason_code IS NOT NULL AND effective_tick IS NULL AND event_seq IS NULL)),
    CONSTRAINT battle_command_receipt_count_ck CHECK (
        server_tick BETWEEN 0 AND 3000 AND authority_revision >= 0)
);

CREATE TABLE battle_snapshot (
    world_id          INTEGER       NOT NULL,
    battle_id         VARCHAR(128)  NOT NULL,
    snapshot_seq      BIGINT        NOT NULL,
    session_epoch     BIGINT        NOT NULL,
    lease_owner       VARCHAR(128)  NOT NULL,
    tick              INTEGER       NOT NULL,
    event_seq         BIGINT        NOT NULL,
    state_hash        VARCHAR(64)   NOT NULL,
    compressed_state  BYTEA         NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT battle_snapshot_pk PRIMARY KEY (world_id, battle_id, snapshot_seq),
    CONSTRAINT battle_snapshot_world_fk FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_snapshot_ticket_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES battle_ticket(world_id, battle_id),
    CONSTRAINT battle_snapshot_count_ck CHECK (
        snapshot_seq > 0 AND session_epoch > 0 AND tick BETWEEN 0 AND 3000 AND event_seq >= 0),
    CONSTRAINT battle_snapshot_hash_ck CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT battle_snapshot_body_ck CHECK (octet_length(compressed_state) > 0)
);

CREATE TABLE battle_result_outbox (
    world_id             INTEGER       NOT NULL,
    battle_id            VARCHAR(128)  NOT NULL,
    result_revision      INTEGER       NOT NULL,
    session_epoch        BIGINT        NOT NULL,
    lease_owner          VARCHAR(128)  NOT NULL,
    result_text          TEXT          NOT NULL,
    result_sha256        VARCHAR(64)   NOT NULL,
    replay_hash          VARCHAR(64)   NOT NULL,
    lock_generation      BIGINT        NOT NULL,
    lock_set_revision    BIGINT        NOT NULL,
    status               VARCHAR(16)   NOT NULL,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT clock_timestamp(),
    applied_at           TIMESTAMPTZ   NULL,
    blocked_at           TIMESTAMPTZ   NULL,
    block_reason         VARCHAR(512)  NULL,
    CONSTRAINT battle_result_outbox_pk PRIMARY KEY (world_id, battle_id, result_revision),
    CONSTRAINT battle_result_outbox_world_fk FOREIGN KEY (world_id) REFERENCES world_state(id),
    CONSTRAINT battle_result_outbox_ticket_fk FOREIGN KEY (world_id, battle_id)
        REFERENCES battle_ticket(world_id, battle_id),
    CONSTRAINT battle_result_outbox_count_ck CHECK (
        result_revision > 0 AND session_epoch > 0 AND lock_generation >= 0 AND lock_set_revision >= 0),
    CONSTRAINT battle_result_outbox_body_ck CHECK (
        jsonb_typeof(result_text::jsonb) = 'object'
        AND result_sha256 ~ '^[0-9a-f]{64}$' AND replay_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT battle_result_outbox_status_ck CHECK (
        (status = 'PENDING' AND applied_at IS NULL AND blocked_at IS NULL AND block_reason IS NULL)
        OR (status = 'APPLIED' AND applied_at IS NOT NULL AND blocked_at IS NULL AND block_reason IS NULL)
        OR (status = 'RESULT_BLOCKED' AND applied_at IS NULL AND blocked_at IS NOT NULL
            AND block_reason IS NOT NULL))
);
CREATE INDEX battle_result_outbox_pending_idx ON battle_result_outbox
    (world_id, status, created_at, battle_id, result_revision);
