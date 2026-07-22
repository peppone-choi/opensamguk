CREATE TABLE IF NOT EXISTS command_inbox (
    world_id integer NOT NULL REFERENCES world_state(id),
    request_id text NOT NULL,
    payload_schema_version integer NOT NULL,
    command_kind text NOT NULL,
    status text NOT NULL,
    intent_fingerprint text NOT NULL,
    general_id integer,
    turn_idx integer,
    action_code text,
    payload jsonb NOT NULL,
    redis_wake_published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (world_id, request_id),
    CONSTRAINT command_inbox_schema_positive CHECK (payload_schema_version > 0),
    CONSTRAINT command_inbox_status_check CHECK (status IN ('ACCEPTED')),
    CONSTRAINT command_inbox_kind_check CHECK (command_kind IN ('IMMEDIATE', 'RESERVED_TURN', 'QUEUE_MUTATION'))
);

CREATE INDEX IF NOT EXISTS idx_command_inbox_world_status_created
    ON command_inbox (world_id, status, created_at);

COMMENT ON TABLE command_inbox IS 'Durable command intake ledger before 202 Accepted (OPENSAM-133)';
COMMENT ON COLUMN command_inbox.payload_schema_version IS 'Command inbox payload schema version';
COMMENT ON COLUMN command_inbox.payload IS 'Encoded TurnDaemonCommandEnvelope JSON payload for Redis wake/replay';
