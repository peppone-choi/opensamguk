package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import opensamguk.common.turn.TurnCatchUp
import java.time.Instant
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * OPENSAM-131 — real [JdbcFlushExecutor] CAS path: matching fence advances version; stale fence rolls back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldVersionCasIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private lateinit var tx: TransactionTemplate

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — skipped",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        tx = TransactionTemplate(DataSourceTransactionManager(ds))
        executor = JdbcFlushExecutor(jdbc, tx)
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, world_version, writer_epoch) " +
                "VALUES (1, 't', 200, 1, 60, 5, 9)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `catch-up column survives CAS and non-CAS flushes`() {
        val start = Instant.parse("2026-09-27T00:00:00Z")
        val plan = TurnCatchUp.start(start.minusSeconds(72000), start)
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, world_version, writer_epoch) " +
                "VALUES (2, 'catchup', 200, 1, 300, 0, 9)",
            MapSqlParameterSource(),
        )
        val version = jdbc.queryForObject(
            "SELECT world_version FROM world_state WHERE id = 2", MapSqlParameterSource(), Long::class.java,
        )!!
        executor.flush(testFlushPayload(WorldId(2), mutableMapOf(
            "id" to 2, "current_year" to 200, "current_month" to 1,
            "expected_world_version" to version, "writer_epoch" to 9L,
            "catch_up" to plan.toMeta(),
        )))
        fun stored() = TurnCatchUp.fromMeta(MetaJson.decode(jdbc.queryForObject(
            "SELECT catch_up::text FROM world_state WHERE id = 2", MapSqlParameterSource(), String::class.java,
        )))
        assertEquals(plan, stored())

        executor.flush(testFlushPayload(WorldId(2), mapOf(
            "id" to 2, "current_year" to 200, "current_month" to 1,
        )))
        assertEquals(plan, stored(), "an ordinary flush must preserve the active plan")

        val finished = plan.copy(active = false)
        executor.flush(testFlushPayload(WorldId(2), mapOf(
            "id" to 2, "current_year" to 200, "current_month" to 1, "catch_up" to finished.toMeta(),
        )))
        assertEquals(finished, stored())
    }

    @Test
    fun `matching CAS advances world_version by one`() {
        executor.flush(
            testFlushPayload(
                worldId = WorldId(1),
                worldStateUpdate = mutableMapOf<String, Any?>(
                    "id" to 1,
                    "current_year" to 200,
                    "current_month" to 2,
                    "expected_world_version" to 5L,
                    "writer_epoch" to 9L,
                ),
            ),
        )
        assertEquals(
            6L,
            jdbc.queryForObject(
                "SELECT world_version FROM world_state WHERE id = 1",
                MapSqlParameterSource(),
                Long::class.java,
            ),
        )
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT current_month FROM world_state WHERE id = 1",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
    }

    @Test
    fun `stale CAS throws and does not advance version`() {
        val before = jdbc.queryForObject(
            "SELECT world_version FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            Long::class.java,
        )!!
        assertFailsWith<StaleWorldWriterException> {
            executor.flush(
                testFlushPayload(
                    worldId = WorldId(1),
                    worldStateUpdate = mutableMapOf<String, Any?>(
                        "id" to 1,
                        "current_year" to 201,
                        "current_month" to 1,
                        "expected_world_version" to 0L, // stale
                        "writer_epoch" to 9L,
                    ),
                ),
            )
        }
        assertEquals(
            before,
            jdbc.queryForObject(
                "SELECT world_version FROM world_state WHERE id = 1",
                MapSqlParameterSource(),
                Long::class.java,
            ),
        )
        assertEquals(
            200, // not 201
            jdbc.queryForObject(
                "SELECT current_year FROM world_state WHERE id = 1",
                MapSqlParameterSource(),
                Int::class.java,
            ),
        )
    }
}
