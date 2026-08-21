package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals

class V43GameServerMigrationTest {

    @Test
    fun `V43 creates ordered gateway game server registry`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable - V43 migration IT skipped",
        )
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                .load()
                .migrate()
            val jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))

            jdbc.update(
                """
                INSERT INTO game_server (
                    server_id, display_name, game_api_url, game_engine_url, deploy_project,
                    generation, scenario_code
                ) VALUES ('live1', 'Live One', 'http://slive1-game-api:8081',
                    'http://slive1-game-engine:8082', 'opensamguk-slive1', 2, 'scenario_1010')
                """.trimIndent(),
            )

            assertEquals(
                listOf("live1", "Live One", "2", "scenario_1010"),
                jdbc.queryForMap(
                    "SELECT server_id, display_name, generation, scenario_code FROM game_server WHERE server_id = 'live1'",
                ).values.map { it.toString() },
            )
        }
    }
}
