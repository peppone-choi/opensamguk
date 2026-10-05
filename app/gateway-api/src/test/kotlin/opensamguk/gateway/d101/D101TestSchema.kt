package opensamguk.gateway.d101

import org.springframework.jdbc.core.JdbcTemplate

/** H2 local fixture only. PostgreSQL IT executes the actual migration source. */
object D101TestSchema {
    fun install(jdbc: JdbcTemplate) {
        jdbc.execute("""CREATE TABLE game_server_operation_reservation (
            operation_id VARCHAR(32) PRIMARY KEY, kind VARCHAR(24) NOT NULL, server_id VARCHAR(48) NOT NULL,
            target_fingerprint VARCHAR(64) NOT NULL, initial_public_revision BIGINT NOT NULL)""")
        jdbc.execute("""CREATE TABLE game_server_d101_execution (
            operation_id VARCHAR(32) PRIMARY KEY REFERENCES game_server_operation_reservation(operation_id),
            server_id VARCHAR(48) NOT NULL, world_id INTEGER NOT NULL, state VARCHAR(32) NOT NULL, last_safe_state VARCHAR(32) NOT NULL,
            intent_sha VARCHAR(64) NOT NULL, intent_bytes BYTEA NOT NULL, gateway_payload_sha VARCHAR(64) NOT NULL,
            prepare_payload BYTEA NOT NULL, target_fingerprint VARCHAR(64) NOT NULL, initial_public_revision BIGINT NOT NULL,
            verifying_revision BIGINT NOT NULL, approval_plan_sha VARCHAR(64), execution_receipt_sha VARCHAR(64),
            root_request_fingerprint VARCHAR(64), root_result_sha VARCHAR(64), root_result_bytes BYTEA,
            published_revision BIGINT, validation_receipt_sha VARCHAR(64), failure_code VARCHAR(64),
            created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)""")
    }
}
