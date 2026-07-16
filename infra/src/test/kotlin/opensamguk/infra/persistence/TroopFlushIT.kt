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
import kotlin.test.assertNull

/**
 * F4 Wave C2 slice B — Testcontainers IT for the `troop` table channel: createMany (NewTroop),
 * UPDATE name (SetTroopName), deleteMany by troop_leader (ExitTroop leader-disband). Proves the SQL
 * the [JdbcFlushExecutor] emits is column-correct against the real V1 baseline `troop` table.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TroopFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1)
    private fun nameOf(leader: Int): String? =
        jdbc.queryForList("SELECT name FROM troop WHERE troop_leader = :id", MapSqlParameterSource("id", leader), String::class.java).firstOrNull()
    private fun count(): Int =
        jdbc.queryForObject("SELECT count(*) FROM troop", MapSqlParameterSource(), Int::class.java)!!

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
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = NamedParameterJdbcTemplate(ds)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(ds)))
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'sc', 200, 1, 3600)",
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `troop create then rename then leader-disband delete`() {
        // create two troops (NewTroop x2).
        executor.flush(
            FlushPayload(
                worldStateUpdate = ws(),
                createdTroops = listOf(TroopRow(7, 1, "제1군단"), TroopRow(8, 1, "제2군단")),
            ),
        )
        assertEquals(2, count())
        assertEquals("제1군단", nameOf(7))

        // rename troop 7 (SetTroopName) — created-this-tick exclusion means this is a separate flush.
        executor.flush(
            FlushPayload(worldStateUpdate = ws(), updatedTroops = listOf(TroopRow(7, 1, "개명군단"))),
        )
        assertEquals("개명군단", nameOf(7))
        assertEquals("제2군단", nameOf(8)) // untouched
        assertEquals(2, count())

        // leader-disband delete of troop 7 (ExitTroop) — by troop_leader.
        executor.flush(
            FlushPayload(worldStateUpdate = ws(), deletedTroops = listOf(7)),
        )
        assertNull(nameOf(7))
        assertEquals("제2군단", nameOf(8))
        assertEquals(1, count())
    }
}
