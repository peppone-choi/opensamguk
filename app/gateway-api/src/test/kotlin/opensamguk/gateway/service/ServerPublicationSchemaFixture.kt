package opensamguk.gateway.service

import org.springframework.jdbc.core.JdbcTemplate

// Registry fixtures exercise the new membership transaction without weakening
// their previous assertions. V75's vendor-specific constraints need PostgreSQL QA.
internal fun createServerPublicationFixture(jdbc: JdbcTemplate) {
    jdbc.execute(
        """
        CREATE TABLE game_server_publication (
            server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE,
            publicly_visible BOOLEAN NOT NULL DEFAULT TRUE, state VARCHAR(16) NOT NULL,
            revision BIGINT NOT NULL,
            operation_id VARCHAR(32),
            expected_generation INTEGER,
            expected_scenario_code TEXT,
            target_fingerprint VARCHAR(64)
        )
        """.trimIndent(),
    )
    // V72 projection for registry tests: preserve the execution identity, pep/world
    // scope, and state constraints read by the D101 registry write fence.
    jdbc.execute(
        """
        CREATE TABLE game_server_d101_execution (
            operation_id VARCHAR(32) PRIMARY KEY,
            server_id VARCHAR(48) NOT NULL CHECK (server_id = 'pep'),
            world_id INTEGER NOT NULL CHECK (world_id = 1),
            state VARCHAR(32) NOT NULL CHECK (state IN (
                'PREPARED', 'DISPATCH_INTENT', 'REMOTE_SUCCEEDED', 'REGISTRY_SETTLED',
                'PUBLISHED', 'RECOVERY_REQUIRED', 'RECOVERED'
            )),
            last_safe_state VARCHAR(32) NOT NULL CHECK (last_safe_state IN (
                'PREPARED', 'DISPATCH_INTENT', 'REMOTE_SUCCEEDED', 'REGISTRY_SETTLED', 'PUBLISHED'
            )),
            CHECK (state = last_safe_state OR state IN ('RECOVERY_REQUIRED', 'RECOVERED'))
        )
        """.trimIndent(),
    )
}
