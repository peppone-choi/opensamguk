CREATE TABLE game_server_registry_transition (
    server_id VARCHAR(48) PRIMARY KEY CHECK (server_id ~ '^[a-z0-9]{1,48}$'),
    action VARCHAR(8) NOT NULL CHECK (action IN ('CREATE', 'CLOSE')),
    display_name TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    game_api_url TEXT NOT NULL,
    game_engine_url TEXT NOT NULL,
    deploy_project TEXT NOT NULL,
    generation INTEGER CHECK (generation >= 0),
    scenario_code TEXT,
    dispatched BOOLEAN NOT NULL DEFAULT FALSE,
    remote_applied BOOLEAN NOT NULL DEFAULT FALSE,
    owner_token VARCHAR(36) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
