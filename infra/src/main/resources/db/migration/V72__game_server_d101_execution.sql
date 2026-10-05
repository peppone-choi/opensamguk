-- Common operation identities outlive registry membership and every execution.
CREATE TABLE game_server_operation_reservation (
    operation_id VARCHAR(32) PRIMARY KEY CHECK (operation_id ~ '^[a-f0-9]{32}$'),
    kind VARCHAR(24) NOT NULL CHECK (kind IN ('PUBLICATION_ONLY','D101_RESET','RECOVERY')),
    server_id VARCHAR(48) NOT NULL CHECK (server_id ~ '^[a-z0-9]{1,48}$'),
    target_fingerprint VARCHAR(64) NOT NULL CHECK (target_fingerprint ~ '^[a-f0-9]{64}$'),
    initial_public_revision BIGINT NOT NULL CHECK (initial_public_revision > 0)
);

-- A legacy publication is history, never a newly authorized D101 reset.
INSERT INTO game_server_operation_reservation
    (operation_id, kind, server_id, target_fingerprint, initial_public_revision)
SELECT operation_id, 'PUBLICATION_ONLY', server_id, target_fingerprint, expected_revision
FROM game_server_publication_operation;

CREATE TABLE game_server_d101_execution (
    operation_id VARCHAR(32) PRIMARY KEY REFERENCES game_server_operation_reservation(operation_id),
    server_id VARCHAR(48) NOT NULL CHECK (server_id = 'pep'),
    world_id INTEGER NOT NULL CHECK (world_id = 1),
    state VARCHAR(32) NOT NULL CHECK (state IN ('PREPARED','DISPATCH_INTENT','REMOTE_SUCCEEDED','REGISTRY_SETTLED','PUBLISHED','RECOVERY_REQUIRED','RECOVERED')),
    last_safe_state VARCHAR(32) NOT NULL CHECK (last_safe_state IN ('PREPARED','DISPATCH_INTENT','REMOTE_SUCCEEDED','REGISTRY_SETTLED','PUBLISHED')),
    intent_sha VARCHAR(64) NOT NULL CHECK (intent_sha ~ '^[a-f0-9]{64}$'),
    intent_bytes BYTEA NOT NULL CHECK (octet_length(intent_bytes) BETWEEN 1 AND 32768),
    gateway_payload_sha VARCHAR(64) NOT NULL CHECK (gateway_payload_sha ~ '^[a-f0-9]{64}$'),
    prepare_payload BYTEA NOT NULL CHECK (octet_length(prepare_payload) BETWEEN 1 AND 65536),
    target_fingerprint VARCHAR(64) NOT NULL CHECK (target_fingerprint ~ '^[a-f0-9]{64}$'),
    initial_public_revision BIGINT NOT NULL CHECK (initial_public_revision > 0),
    verifying_revision BIGINT NOT NULL CHECK (verifying_revision > initial_public_revision),
    approval_plan_sha VARCHAR(64) CHECK (approval_plan_sha ~ '^[a-f0-9]{64}$'),
    execution_receipt_sha VARCHAR(64) CHECK (execution_receipt_sha ~ '^[a-f0-9]{64}$'),
    root_request_fingerprint VARCHAR(64) CHECK (root_request_fingerprint ~ '^[a-f0-9]{64}$'),
    root_result_sha VARCHAR(64) CHECK (root_result_sha ~ '^[a-f0-9]{64}$'),
    root_result_bytes BYTEA CHECK (octet_length(root_result_bytes) BETWEEN 1 AND 16384),
    published_revision BIGINT CHECK (published_revision > verifying_revision),
    validation_receipt_sha VARCHAR(64) CHECK (validation_receipt_sha ~ '^[a-f0-9]{64}$'),
    failure_code VARCHAR(64) CHECK (failure_code IN ('ROOT_FAILED','ROOT_CANCELLED','RECOVERY_REQUIRED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (updated_at >= created_at),
    CHECK (state = last_safe_state OR state IN ('RECOVERY_REQUIRED','RECOVERED')),
    CHECK ((state IN ('RECOVERY_REQUIRED','RECOVERED')) = (failure_code IS NOT NULL)),
    CHECK (
        (last_safe_state = 'PREPARED' AND approval_plan_sha IS NULL AND execution_receipt_sha IS NULL AND root_request_fingerprint IS NULL)
        OR
        (last_safe_state IN ('DISPATCH_INTENT','REMOTE_SUCCEEDED','REGISTRY_SETTLED','PUBLISHED')
            AND approval_plan_sha IS NOT NULL AND execution_receipt_sha IS NOT NULL AND root_request_fingerprint IS NOT NULL)
    ),
    CHECK (
        (last_safe_state IN ('PREPARED','DISPATCH_INTENT') AND root_result_sha IS NULL AND root_result_bytes IS NULL)
        OR
        (last_safe_state IN ('REMOTE_SUCCEEDED','REGISTRY_SETTLED','PUBLISHED') AND root_result_sha IS NOT NULL AND root_result_bytes IS NOT NULL)
    ),
    CHECK (
        (last_safe_state <> 'PUBLISHED' AND published_revision IS NULL AND validation_receipt_sha IS NULL)
        OR
        (last_safe_state = 'PUBLISHED' AND published_revision IS NOT NULL AND validation_receipt_sha IS NOT NULL)
    )
);
CREATE UNIQUE INDEX game_server_d101_one_active
    ON game_server_d101_execution(server_id) WHERE state NOT IN ('PUBLISHED','RECOVERED');
