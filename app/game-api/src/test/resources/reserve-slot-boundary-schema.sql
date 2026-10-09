-- Synthetic intake-only schema for ReservationSlotBoundaryIT. Never connects to an application database.
CREATE TABLE world_state (
    id integer PRIMARY KEY, config jsonb NOT NULL DEFAULT '{}'
);

CREATE TABLE general (
    world_id integer NOT NULL REFERENCES world_state(id), id integer NOT NULL, PRIMARY KEY(world_id,id)
);

CREATE TABLE general_turn (
    id serial PRIMARY KEY,
    world_id integer NOT NULL REFERENCES world_state(id),
    general_id integer NOT NULL,
    turn_idx integer NOT NULL,
    action_code text NOT NULL,
    arg jsonb NOT NULL DEFAULT '{}',
    brief text NOT NULL DEFAULT '휴식',
    request_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(world_id, general_id, turn_idx)
);

CREATE TABLE command_inbox (
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
    owner_user_id integer,
    redis_wake_published_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(world_id, request_id)
);

CREATE TABLE command_result (
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
    PRIMARY KEY(world_id, request_id, result_seq)
);

CREATE TABLE command_outbox (
    world_id integer NOT NULL REFERENCES world_state(id),
    event_id text NOT NULL,
    request_id text NOT NULL,
    event_type text NOT NULL,
    payload_schema_version integer NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    PRIMARY KEY(world_id, event_id)
);
