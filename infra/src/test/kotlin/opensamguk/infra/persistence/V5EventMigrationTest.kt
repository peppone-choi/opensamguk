package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Testcontainers IT for the FE3 `V5__p3_event` Flyway migration.
 *
 * Brings up `postgres:16-alpine`, applies the FULL Flyway chain through V5, and asserts:
 *  - the chain applies cleanly (V5 does NOT `CREATE TABLE event` — it reuses the V1 baseline table,
 *    so there is no "relation event already exists" error);
 *  - the new `(target_code, priority, id)` covering index exists for the dispatch order
 *    `WHERE target_code = ? ORDER BY priority DESC, id ASC`;
 *  - the reused `event` table still has the baseline shape (target_code / priority / condition /
 *    action jsonb) and accepts the F2 seed columns.
 *
 * The V1 baseline `event` table is left untouched (frozen). V4 is the separate F4 calendar
 * migration; Flyway applies versions in order and tolerates a gap if F4 has not yet merged.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V5EventMigrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()

        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }

        // The full chain through V5 must apply without error (no duplicate event-table creation).
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        jdbc = NamedParameterJdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `the V1 baseline event table is reused (single relation) with its baseline columns`() {
        val tableCount = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.tables WHERE table_name = 'event'",
            MapSqlParameterSource(),
            Int::class.java,
        )
        assertEquals(1, tableCount, "exactly one 'event' relation (V5 reuses V1's, does not recreate)")

        val cols = jdbc.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'event'",
            MapSqlParameterSource(),
            String::class.java,
        )
        assertTrue(cols.containsAll(listOf("id", "target_code", "priority", "condition", "action")))
    }

    @Test
    fun `the V5 dispatch-order index exists on (target_code, priority, id)`() {
        val indexDef = jdbc.queryForObject(
            "SELECT indexdef FROM pg_indexes WHERE indexname = 'event_target_priority_id_idx'",
            MapSqlParameterSource(),
            String::class.java,
        )!!
        // Postgres normalizes the columns into the index definition; assert all three participate.
        assertTrue(indexDef.contains("target_code"), "index covers target_code")
        assertTrue(indexDef.contains("priority"), "index covers priority")
        assertTrue(indexDef.contains("id"), "index covers id")
    }

    @Test
    fun `the reused event table accepts an F2-seeded row in dispatch order`() {
        jdbc.update(
            """
            INSERT INTO event (target_code, priority, condition, action)
            VALUES ('pre_month', 9000, 'true'::jsonb,
                    '[["UpdateCitySupply"],["ProcessWarIncome"]]'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val priority = jdbc.queryForObject(
            "SELECT priority FROM event WHERE target_code = 'pre_month' ORDER BY priority DESC, id ASC LIMIT 1",
            MapSqlParameterSource(),
            Int::class.java,
        )
        assertEquals(9000, priority)
    }
}
