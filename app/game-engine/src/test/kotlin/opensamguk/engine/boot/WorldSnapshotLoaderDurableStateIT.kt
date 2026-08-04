package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldSnapshotLoaderDurableStateIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun setUp() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — durable loader IT skipped (not failed)",
        )
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `restart snapshot restores GAME plock PHP starttime and canonical world start time`() {
        jdbc.update(
            """
            INSERT INTO world_state (
                id, scenario_code, current_year, current_month, tick_seconds, status, meta, config, start_time
            ) VALUES (
                1, 'durable_lock', 200, 1, 1800, 'PRE_OPEN',
                '{"startTime":"obsolete"}'::jsonb,
                '{"turnterm":30,"startTime":"obsolete"}'::jsonb,
                TIMESTAMPTZ '0200-01-01 00:00:00+00'
            )
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO game_kv (world_id, "table", namespace, key, value)
            VALUES
              (1, 'game_env', 'game_env', 'plock', '1'::jsonb),
              (1, 'game_env', 'game_env', 'starttime', '"0200-01-01 09:00:00"'::jsonb)
            """.trimIndent(),
        )

        val state = WorldSnapshotLoader(
            jdbc,
            SeedBootstrap(scenarioCode = "scenario_0", seedEnabled = false, worldId = WorldId(1)),
            WorldId(1),
        ).buildSnapshot().state

        assertEquals(1, (state.meta["plock"] as Number).toInt())
        assertEquals("0200-01-01 09:00:00", state.meta["starttime"])
        assertEquals("0200-01-01T00:00:00Z", state.meta["startTime"])
        assertEquals("0200-01-01T00:00:00Z", state.config["startTime"])
        assertEquals("PRE_OPEN", state.status)
        assertEquals(1_800, state.tickSeconds)
    }
}
