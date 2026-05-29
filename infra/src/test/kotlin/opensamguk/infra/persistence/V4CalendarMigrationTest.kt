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
import kotlin.test.assertNull

/**
 * Testcontainers IT for the FC1 `V4__p3_calendar` Flyway migration.
 *
 * Brings up `postgres:16-alpine`, applies the FULL Flyway chain (V1 → V2 → V3 → V4), and asserts the
 * V4 migration:
 *  - added the calendar columns to `world_state`: `start_year int`, `start_time timestamptz`,
 *    `turn_term int`, `isunited int NOT NULL DEFAULT 0`, `hidden_seed text` — the source `ServerClock`
 *    / `MonthlyPipeline` / `MonthScopedRng` read (`turnDate` reads startyear/starttime/turnterm;
 *    `executeAllCommand` freezes on `isunited==2|3`; the month-scoped RNG seeds with `hidden_seed`).
 *  - the P0/P1 blocker schema fixes: `city.trade` is now NULLABLE with NO default (RandomizeCityTradeRate
 *    flushes literal NULL for disabled cities — the baseline `NOT NULL DEFAULT 100` would coerce/throw),
 *    and `city.trust` is now `double precision` (fractional trust accumulation + the trust<30 neutralize
 *    threshold must survive flush↔reload).
 *
 * V1-V3 (frozen baselines) are untouched. Existing INSERTs that omit the new world_state columns must
 * still succeed (the new columns are nullable or defaulted).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V4CalendarMigrationTest {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()

        dataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }

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
    fun `world_state gains the calendar columns with the committed shape`() {
        assertCol("world_state", "start_year", dataType = "integer", nullable = "YES")
        assertCol("world_state", "start_time", dataType = "timestamp with time zone", nullable = "YES")
        assertCol("world_state", "turn_term", dataType = "integer", nullable = "YES")
        assertCol("world_state", "isunited", dataType = "integer", nullable = "NO", default = "0")
        assertCol("world_state", "hidden_seed", dataType = "text", nullable = "YES")
    }

    @Test
    fun `existing world_state INSERT that omits the new columns still succeeds and defaults isunited to 0`() {
        // Baseline-shape INSERT (the P1/P2 IT seed pattern: omits start_year/turn_term/hidden_seed).
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'scenario_1010', 181, 1, 3600)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val row = jdbc.queryForMap(
            "SELECT isunited, start_year, hidden_seed FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
        )
        assertEquals(0, row["isunited"], "isunited defaults to 0")
        assertNull(row["start_year"], "start_year is null when omitted")
        assertNull(row["hidden_seed"], "hidden_seed is null when omitted")
    }

    @Test
    fun `world_state threads the config-sourced startYear and the 32-char lowercase hex hidden_seed`() {
        jdbc.update(
            """
            INSERT INTO world_state
                (id, scenario_code, current_year, current_month, tick_seconds,
                 start_year, start_time, turn_term, isunited, hidden_seed)
            VALUES
                (2, 'scenario_1010', 181, 1, 3600,
                 181, TIMESTAMPTZ '2026-05-30 01:00:00+00', 60, 0,
                 '8ebfeb6fa932a181ec9ef43b7473f4c9')
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        val row = jdbc.queryForMap(
            "SELECT start_year, turn_term, isunited, hidden_seed FROM world_state WHERE id = 2",
            MapSqlParameterSource(),
        )
        assertEquals(181, row["start_year"], "scenario_1010 startYear=181 round-trips")
        assertEquals(60, row["turn_term"])
        assertEquals(0, row["isunited"])
        val seed = row["hidden_seed"] as String
        // FC1 assertion: the threaded seed is the live config-file value — 32-char lowercase hex.
        assertEquals(32, seed.length, "hidden_seed is bin2hex(random_bytes(16)) = 32 hex chars")
        assertEquals(true, seed.matches(Regex("^[0-9a-f]{32}$")), "hidden_seed is lowercase hex: $seed")
    }

    @Test
    fun `city trade drops NOT NULL and DEFAULT -- a literal NULL persists`() {
        assertCol("city", "trade", dataType = "integer", nullable = "YES", default = null)
        seedCity(id = 10, trade = null, trust = "0")
        val trade = jdbc.queryForMap(
            "SELECT trade FROM city WHERE id = 10",
            MapSqlParameterSource(),
        )["trade"]
        assertNull(trade, "RandomizeCityTradeRate's prob-0 / failed-roll city persists trade IS NULL")
    }

    @Test
    fun `city trust is double precision -- a fractional value round-trips losslessly`() {
        assertCol("city", "trust", dataType = "double precision", nullable = "NO")
        seedCity(id = 11, trade = 100, trust = "42.5")
        val trust = jdbc.queryForObject(
            "SELECT trust FROM city WHERE id = 11",
            MapSqlParameterSource(),
            java.lang.Double::class.java,
        )
        assertEquals(42.5, trust?.toDouble(), "fractional trust survives flush↔reload (no int coercion)")
    }

    private fun seedCity(id: Int, trade: Int?, trust: String) {
        jdbc.update(
            """
            INSERT INTO city
                (id, name, level, pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                 trust, trade, def, def_max, wall, wall_max, region)
            VALUES
                (:id, 'testcity', 5, 1000, 2000, 500, 1000, 500, 1000, 500, 1000,
                 :trust, :trade, 100, 200, 100, 200, 1)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("trust", trust.toDouble())
                .addValue("trade", trade),
        )
    }

    private fun assertCol(
        table: String,
        column: String,
        dataType: String,
        nullable: String,
        default: String? = "__ANY__",
    ) {
        val row = jdbc.queryForMap(
            """
            SELECT data_type, is_nullable, column_default
              FROM information_schema.columns
             WHERE table_name = :t AND column_name = :c
            """.trimIndent(),
            MapSqlParameterSource().addValue("t", table).addValue("c", column),
        )
        assertEquals(dataType, row["data_type"], "$table.$column data_type")
        assertEquals(nullable, row["is_nullable"], "$table.$column is_nullable")
        if (default != "__ANY__") {
            assertEquals(default, row["column_default"], "$table.$column column_default")
        }
    }
}
