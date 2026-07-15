package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class V28YearbookServerIdMigrationTest {

    @Test
    fun `V28 backfills yearbook server_id from authoritative active world server`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V28 migration IT skipped",
        )
        withPostgres { postgres ->
            val jdbc = migrateTo27(postgres)
            jdbc.update(
                "INSERT INTO ng_games (server_id, date, season, scenario, scenario_name, env) VALUES ('active-server', now(), 1, 1010, '영웅집결', '{}'::jsonb)",
            )
            jdbc.update(
                "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, meta) VALUES (1, 'scenario_1010', 181, 1, 3600, '{\"serverId\":\"active-server\"}'::jsonb)",
            )
            jdbc.update(
                "INSERT INTO yearbook_history (profile_name, year, month, map, nations) VALUES ('profile-s1', 181, 1, '{}'::jsonb, '[]'::jsonb)",
            )

            migrateAll(postgres)

            assertEquals(
                "active-server",
                jdbc.queryForObject("SELECT server_id FROM yearbook_history WHERE year = 181 AND month = 1", String::class.java),
            )
        }
    }

    @Test
    fun `V28 refuses ambiguous yearbook backfill instead of copying profile name`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — V28 migration IT skipped",
        )
        withPostgres { postgres ->
            val jdbc = migrateTo27(postgres)
            jdbc.update(
                """
                INSERT INTO ng_games (server_id, date, season, scenario, scenario_name, env)
                VALUES
                  ('server-a', now(), 1, 1010, 'A', '{}'::jsonb),
                  ('server-b', now(), 1, 1010, 'B', '{}'::jsonb)
                """.trimIndent(),
            )
            jdbc.update(
                "INSERT INTO yearbook_history (profile_name, year, month, map, nations) VALUES ('profile-s1', 181, 1, '{}'::jsonb, '[]'::jsonb)",
            )

            assertFailsWith<FlywayException> {
                migrateAll(postgres)
            }
        }
    }

    private fun withPostgres(block: (PostgreSQLContainer<*>) -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            block(postgres)
        }
    }

    private fun migrateTo27(postgres: PostgreSQLContainer<*>): JdbcTemplate {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("27"))
            .load()
            .migrate()
        return JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    private fun migrateAll(postgres: PostgreSQLContainer<*>) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
