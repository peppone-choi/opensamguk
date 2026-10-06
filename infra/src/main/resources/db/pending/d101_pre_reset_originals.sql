-- Source draft only. Assign V<fresh main highest+1> after integration review.
-- The C8 PREPARE hook inserts in the same transaction before PUBLIC -> VERIFYING.
CREATE TABLE game_server_d101_pre_reset_originals (
    operation_id VARCHAR(32) PRIMARY KEY CHECK (operation_id ~ '^[a-f0-9]{32}$'),
    intent_sha VARCHAR(64) NOT NULL CHECK (intent_sha ~ '^[a-f0-9]{64}$'),
    target_fingerprint VARCHAR(64) NOT NULL CHECK (target_fingerprint ~ '^[a-f0-9]{64}$'),
    gateway_payload_sha VARCHAR(64) NOT NULL CHECK (gateway_payload_sha ~ '^[a-f0-9]{64}$'),
    initial_public_revision BIGINT NOT NULL CHECK (initial_public_revision > 0),
    original_sha VARCHAR(64) NOT NULL CHECK (original_sha ~ '^[a-f0-9]{64}$'),
    original_bytes BYTEA NOT NULL CHECK (octet_length(original_bytes) BETWEEN 1 AND 16384)
);
