package opensamguk.gateway.service

import org.springframework.jdbc.core.JdbcTemplate

// Registry fixtures exercise the new membership transaction without weakening
// their previous assertions. V75's vendor-specific constraints need PostgreSQL QA.
internal fun createServerPublicationFixture(jdbc: JdbcTemplate) {
    jdbc.execute(
        """
        CREATE TABLE game_server_publication (
            server_id VARCHAR(48) PRIMARY KEY REFERENCES game_server(server_id) ON DELETE CASCADE,
            state VARCHAR(16) NOT NULL,
            revision BIGINT NOT NULL,
            operation_id VARCHAR(32),
            expected_generation INTEGER,
            expected_scenario_code TEXT,
            target_fingerprint VARCHAR(64)
        )
        """.trimIndent(),
    )
}
