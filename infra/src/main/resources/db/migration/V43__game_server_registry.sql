CREATE TABLE game_server (
    sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    server_id VARCHAR(48) PRIMARY KEY CHECK (server_id ~ '^[a-z0-9]{1,48}$'),
    display_name TEXT NOT NULL CHECK (btrim(display_name) <> ''),
    game_api_url TEXT NOT NULL,
    game_engine_url TEXT NOT NULL,
    deploy_project TEXT NOT NULL,
    generation INTEGER CHECK (generation >= 0),
    scenario_code TEXT
);
