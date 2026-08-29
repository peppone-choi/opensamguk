package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V45ServerRegistryResetTransitionMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - V45 migration IT skipped",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V45 preserves CREATE transitions and accepts RESET transitions`() {
        migrateTo("44")
        jdbc.update(
            """
            INSERT INTO game_server_registry_transition (
                server_id, action, display_name, game_api_url, game_engine_url, deploy_project,
                generation, scenario_code, operation_id, request_fingerprint, owner_token, lease_until
            ) VALUES (
                'create1', 'CREATE', 'Create One', 'http://screate1-game-api:8081',
                'http://screate1-game-engine:8082', 'opensamguk-screate1', 1, 'scenario_1001',
                '11111111111111111111111111111111',
                'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                '11111111-1111-1111-1111-111111111111', CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )

        migrateTo("45")

        assertEquals(
            listOf("create1", "CREATE", "11111111111111111111111111111111"),
            jdbc.queryForMap(
                "SELECT server_id, action, operation_id FROM game_server_registry_transition WHERE server_id = 'create1'",
            ).values.map { it.toString() },
        )
        assertEquals(
            1,
            jdbc.update(
                """
                INSERT INTO game_server_registry_transition (
                    server_id, action, display_name, game_api_url, game_engine_url, deploy_project,
                    generation, scenario_code, operation_id, request_fingerprint, owner_token, lease_until
                ) VALUES (
                    'reset1', 'RESET', 'Reset One', 'http://sreset1-game-api:8081',
                    'http://sreset1-game-engine:8082', 'opensamguk-sreset1', 2, 'scenario_1010',
                    '22222222222222222222222222222222',
                    'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
                    '22222222-2222-2222-2222-222222222222', CURRENT_TIMESTAMP
                )
                """.trimIndent(),
            ),
        )
    }

    private fun migrateTo(target: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .placeholders(mapOf("scenario_dir" to ""))
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }
}
