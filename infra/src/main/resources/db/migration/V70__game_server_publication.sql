-- Assigned V70 after fresh main highest version V69; unapplied source only.
-- The FK deliberately fails if the common V43 registry migration is unavailable.
CREATE TABLE game_server_publication (
    server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PUBLIC', 'VERIFYING')),
    revision BIGINT NOT NULL CHECK (revision > 0),
    operation_id VARCHAR(32) UNIQUE CHECK (operation_id ~ '^[a-f0-9]{32}$'),
    expected_generation INTEGER CHECK (expected_generation >= 0),
    expected_scenario_code TEXT CHECK (expected_scenario_code ~ '^[A-Za-z0-9_.:-]+$'),
    target_fingerprint VARCHAR(64) CHECK (target_fingerprint ~ '^[a-f0-9]{64}$'),
    CHECK (
        (operation_id IS NULL AND expected_generation IS NULL
            AND expected_scenario_code IS NULL AND target_fingerprint IS NULL AND state = 'PUBLIC')
        OR
        (operation_id IS NOT NULL AND expected_generation IS NOT NULL
            AND expected_scenario_code IS NOT NULL AND target_fingerprint IS NOT NULL)
    )
);

-- Preserve currently registered availability; reset must explicitly close it.
INSERT INTO game_server_publication (server_id, state, revision)
SELECT server_id, 'PUBLIC', 1 FROM game_server;

-- Retain operation identity after membership close/delete and re-registration.
-- A FK cascade here would allow historical operation IDs to be reused.
CREATE TABLE game_server_publication_operation (
    operation_id VARCHAR(32) PRIMARY KEY CHECK (operation_id ~ '^[a-f0-9]{32}$'),
    server_id VARCHAR(48) NOT NULL CHECK (server_id ~ '^[a-z0-9]{1,48}$'),
    expected_generation INTEGER NOT NULL CHECK (expected_generation >= 0),
    expected_scenario_code TEXT NOT NULL CHECK (expected_scenario_code ~ '^[A-Za-z0-9_.:-]+$'),
    target_fingerprint VARCHAR(64) NOT NULL CHECK (target_fingerprint ~ '^[a-f0-9]{64}$'),
    expected_revision BIGINT NOT NULL CHECK (expected_revision > 0),
    verifying_revision BIGINT NOT NULL CHECK (verifying_revision > expected_revision),
    published_revision BIGINT,
    validation_receipt_sha256 VARCHAR(64),
    CHECK (
        (published_revision IS NULL AND validation_receipt_sha256 IS NULL)
        OR
        (published_revision IS NOT NULL AND published_revision > verifying_revision
            AND validation_receipt_sha256 IS NOT NULL
            AND validation_receipt_sha256 ~ '^[a-f0-9]{64}$')
    )
);
