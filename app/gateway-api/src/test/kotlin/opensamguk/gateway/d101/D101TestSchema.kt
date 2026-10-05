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
        jdbc.execute("""CREATE TABLE game_server_d101_recovery (
            operation_id VARCHAR(32) PRIMARY KEY REFERENCES game_server_d101_execution(operation_id),
            begin_request_sha VARCHAR(64) NOT NULL, begin_request_bytes BYTEA NOT NULL,
            begin_receipt_sha VARCHAR(64) NOT NULL, begin_receipt_bytes BYTEA NOT NULL,
            root_result_sha VARCHAR(64) NOT NULL, root_result_bytes BYTEA NOT NULL,
            close_request_sha VARCHAR(64), close_request_bytes BYTEA, close_result_sha VARCHAR(64), close_result_bytes BYTEA,
            old_registry_sha VARCHAR(64), old_registry_bytes BYTEA, old_world_sha VARCHAR(64), old_world_bytes BYTEA,
            old_publication_sha VARCHAR(64), backup_manifest_sha VARCHAR(64),
            old_name TEXT, old_game_api_url TEXT, old_game_engine_url TEXT, old_deploy_project TEXT,
            old_generation INTEGER, old_scenario_code TEXT)""")
    }
}
