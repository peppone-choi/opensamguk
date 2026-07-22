ALTER TABLE command_inbox
    DROP CONSTRAINT IF EXISTS command_inbox_status_check;

ALTER TABLE command_inbox
    ADD CONSTRAINT command_inbox_status_check
    CHECK (status IN ('ACCEPTED', 'CLAIMED', 'APPLIED', 'REJECTED'));

ALTER TABLE command_inbox
    ADD COLUMN IF NOT EXISTS claimed_at timestamptz,
    ADD COLUMN IF NOT EXISTS claim_expires_at timestamptz;

ALTER TABLE general_turn
    ADD COLUMN IF NOT EXISTS request_id text;

ALTER TABLE nation_turn
    ADD COLUMN IF NOT EXISTS request_id text;

CREATE TABLE IF NOT EXISTS command_result (
    world_id integer NOT NULL REFERENCES world_state(id),
    request_id text NOT NULL,
    result_seq integer NOT NULL,
    terminal_status text NOT NULL,
    result_type text NOT NULL,
    ok boolean NOT NULL,
    committed_world_version bigint NOT NULL,
    payload_schema_version integer NOT NULL,
    result_payload jsonb NOT NULL,
    sent_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, request_id, result_seq),
    CONSTRAINT command_result_status_check CHECK (terminal_status IN ('APPLIED', 'REJECTED')),
    CONSTRAINT command_result_seq_positive CHECK (result_seq > 0),
    CONSTRAINT command_result_schema_positive CHECK (payload_schema_version > 0),
    CONSTRAINT command_result_world_version_non_negative CHECK (committed_world_version >= 0)
);

CREATE TABLE IF NOT EXISTS command_outbox (
    world_id integer NOT NULL REFERENCES world_state(id),
    event_id text NOT NULL,
    request_id text NOT NULL,
    event_type text NOT NULL,
    payload_schema_version integer NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    PRIMARY KEY (world_id, event_id),
    CONSTRAINT command_outbox_schema_positive CHECK (payload_schema_version > 0)
);

CREATE INDEX IF NOT EXISTS idx_command_result_world_created
    ON command_result (world_id, created_at);

CREATE INDEX IF NOT EXISTS idx_command_outbox_world_unpublished
    ON command_outbox (world_id, created_at)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_command_inbox_world_claimable
    ON command_inbox (world_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_general_turn_world_request
    ON general_turn (world_id, request_id)
    WHERE request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_nation_turn_world_request
    ON nation_turn (world_id, request_id)
    WHERE request_id IS NOT NULL;

COMMENT ON TABLE command_result IS 'Durable command terminal result fallback (OPENSAM-135)';
COMMENT ON COLUMN command_result.result_payload IS 'Encoded TurnDaemonEventEnvelope(commandResult) JSON payload';
COMMENT ON TABLE command_outbox IS 'Transactional outbox for command result events (OPENSAM-135)';
