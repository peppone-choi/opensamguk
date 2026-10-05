-- Spring context tests use H2 with Flyway disabled. Mirror the JDBC registry
-- tables needed while ServerRegistry starts.
CREATE TABLE IF NOT EXISTS game_server (
    sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
    server_id VARCHAR(48) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    game_api_url VARCHAR(255) NOT NULL,
    game_engine_url VARCHAR(255) NOT NULL,
    deploy_project VARCHAR(100) NOT NULL,
    generation INTEGER,
    scenario_code VARCHAR(100)
);

CREATE TABLE IF NOT EXISTS game_server_registry_seed_state (
    id SMALLINT PRIMARY KEY,
    initialized BOOLEAN NOT NULL
);

INSERT INTO game_server_registry_seed_state (id, initialized)
SELECT 1, FALSE
WHERE NOT EXISTS (SELECT 1 FROM game_server_registry_seed_state WHERE id = 1);

CREATE TABLE IF NOT EXISTS game_server_publication (
    server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL,
    operation_id VARCHAR(32),
    expected_generation INTEGER,
    expected_scenario_code TEXT,
    target_fingerprint VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS game_server_publication_operation (
    operation_id VARCHAR(32) PRIMARY KEY,
    server_id VARCHAR(48) NOT NULL,
    expected_generation INTEGER NOT NULL,
    expected_scenario_code TEXT NOT NULL,
    target_fingerprint VARCHAR(64) NOT NULL,
    expected_revision BIGINT NOT NULL,
    verifying_revision BIGINT NOT NULL,
    published_revision BIGINT,
    validation_receipt_sha256 VARCHAR(64)
);
