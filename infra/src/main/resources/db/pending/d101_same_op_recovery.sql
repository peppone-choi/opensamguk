-- Source draft only. Assign V<fresh main highest+1> immediately before the
-- reviewed integration candidate; this directory is not Flyway's migration path.
CREATE TABLE game_server_d101_recovery (
    operation_id VARCHAR(32) PRIMARY KEY REFERENCES game_server_d101_execution(operation_id),
    begin_request_sha VARCHAR(64) NOT NULL CHECK (begin_request_sha ~ '^[a-f0-9]{64}$'),
    begin_request_bytes BYTEA NOT NULL CHECK (octet_length(begin_request_bytes) BETWEEN 1 AND 16384),
    begin_receipt_sha VARCHAR(64) NOT NULL CHECK (begin_receipt_sha ~ '^[a-f0-9]{64}$'),
    begin_receipt_bytes BYTEA NOT NULL CHECK (octet_length(begin_receipt_bytes) BETWEEN 1 AND 16384),
    root_result_sha VARCHAR(64) NOT NULL CHECK (root_result_sha ~ '^[a-f0-9]{64}$'),
    root_result_bytes BYTEA NOT NULL CHECK (octet_length(root_result_bytes) BETWEEN 1 AND 16384),
    close_request_sha VARCHAR(64) CHECK (close_request_sha ~ '^[a-f0-9]{64}$'),
    close_request_bytes BYTEA CHECK (octet_length(close_request_bytes) BETWEEN 1 AND 16384),
    close_result_sha VARCHAR(64) CHECK (close_result_sha ~ '^[a-f0-9]{64}$'),
    close_result_bytes BYTEA CHECK (octet_length(close_result_bytes) BETWEEN 1 AND 16384),
    old_registry_sha VARCHAR(64) CHECK (old_registry_sha ~ '^[a-f0-9]{64}$'),
    old_registry_bytes BYTEA CHECK (octet_length(old_registry_bytes) BETWEEN 1 AND 16384),
    old_world_sha VARCHAR(64) CHECK (old_world_sha ~ '^[a-f0-9]{64}$'),
    old_world_bytes BYTEA CHECK (octet_length(old_world_bytes) BETWEEN 1 AND 16384),
    old_publication_sha VARCHAR(64) CHECK (old_publication_sha ~ '^[a-f0-9]{64}$'),
    backup_manifest_sha VARCHAR(64) CHECK (backup_manifest_sha ~ '^[a-f0-9]{64}$'),
    old_name TEXT CHECK (old_name IS NULL OR btrim(old_name) <> ''),
    old_game_api_url TEXT,
    old_game_engine_url TEXT,
    old_deploy_project TEXT,
    old_generation INTEGER CHECK (old_generation >= 0),
    old_scenario_code TEXT CHECK (old_scenario_code ~ '^scenario_[0-9]+$'),
    CHECK (
        (close_request_sha IS NULL AND close_request_bytes IS NULL AND close_result_sha IS NULL AND
         close_result_bytes IS NULL AND old_registry_sha IS NULL AND old_registry_bytes IS NULL AND
         old_world_sha IS NULL AND old_world_bytes IS NULL AND old_publication_sha IS NULL AND
         backup_manifest_sha IS NULL AND old_name IS NULL AND old_game_api_url IS NULL AND
         old_game_engine_url IS NULL AND old_deploy_project IS NULL AND old_generation IS NULL AND
         old_scenario_code IS NULL)
        OR
        (close_request_sha IS NOT NULL AND close_request_bytes IS NOT NULL AND close_result_sha IS NOT NULL AND
         close_result_bytes IS NOT NULL AND old_registry_sha IS NOT NULL AND old_registry_bytes IS NOT NULL AND
         old_world_sha IS NOT NULL AND old_world_bytes IS NOT NULL AND old_publication_sha IS NOT NULL AND
         backup_manifest_sha IS NOT NULL AND old_name IS NOT NULL AND old_game_api_url IS NOT NULL AND
         old_game_engine_url IS NOT NULL AND old_deploy_project IS NOT NULL AND old_generation IS NOT NULL AND
         old_scenario_code IS NOT NULL)
    )
);
