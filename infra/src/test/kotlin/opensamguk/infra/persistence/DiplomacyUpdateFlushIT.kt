package opensamguk.infra.persistence

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

/**
 * T0.4 — Testcontainers IT for the per-command diplomacy UPDATE step + the collision regression
 * against the monthly TICK's bulk-SQL update (commands during the pass, tick AFTER).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiplomacyUpdateFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val ds: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration").load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))

        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
        jdbc.update(
            """
            INSERT INTO diplomacy (src_nation_id, dest_nation_id, state_code, term, is_dead) VALUES
              (1, 2, 2, 0, false), (2, 1, 2, 0, false)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun stateOf(src: Int, dest: Int): Pair<Int, Int> {
        val r = jdbc.queryForMap(
            "SELECT state_code, term FROM diplomacy WHERE src_nation_id = :s AND dest_nation_id = :d",
            MapSqlParameterSource().addValue("s", src).addValue("d", dest),
        )
        return intOf(r["state_code"]) to intOf(r["term"])
    }

    @Test
    fun `per-command diplomacy UPDATE writes both directions then the tick bulk-SQL runs after`() {
        // --- the per-command flush (선전포고: state 2 -> 1, term -> 24 BOTH rows) -----------------
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 200, "current_month" to 1),
                updatedDiplomacy = listOf(
                    DiplomacyUpdate(1, 2, state = 1, term = 24),
                    DiplomacyUpdate(2, 1, state = 1, term = 24),
                ),
            ),
        )
        assertEquals(1 to 24, stateOf(1, 2))
        assertEquals(1 to 24, stateOf(2, 1))

        // --- the monthly TICK bulk-SQL diplomacy update runs AFTER (term decrement) — no collision --
        jdbc.update("UPDATE diplomacy SET term = term - 1 WHERE state_code = 1 AND term > 0", MapSqlParameterSource())
        assertEquals(1 to 23, stateOf(1, 2))
        assertEquals(1 to 23, stateOf(2, 1))
    }
}
