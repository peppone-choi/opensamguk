package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
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
 * Testcontainers IT for [JdbcFlushExecutor]. Brings up `postgres:16-alpine`, applies the Flyway
 * baseline, seeds one general + one city, builds a `FlushPayload` representing a `che_농지개간`
 * post-state, flushes, SELECTs the rows back, and asserts:
 *  - the general/city scalar columns + `meta` jsonb match the hand-built expected post-state
 *    byte-for-byte (the AREA G golden DB fragment is the final authority once generated),
 *  - exactly ONE general UPDATE, ONE city UPDATE, ONE log_entry INSERT fired,
 *  - the recorded op sequence == the databaseHooks ordered contract for the P1 slice.
 *
 * The executor is driven through a [DataSourceTransactionManager]-backed [TransactionTemplate]
 * (NOT a JPA tx manager) — the write path never binds an EntityManager.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcFlushExecutorIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var dataSource: DataSource
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

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
        val txManager = DataSourceTransactionManager(dataSource)
        executor = JdbcFlushExecutor(jdbc, TransactionTemplate(txManager))

        seed()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun seed() {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'scenario_2', 190, 1, 3600)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // Seed one general (pre-state: gold 1000, agri-cultivating actor).
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, gold, rice, turn_time, meta)
            VALUES
                (10, '장수십', 2, 5, 70, 65, 80, 0, 0, 0, 4, 1000, 1000, now(),
                 CAST('{"explevel":1,"intel_exp":0,"max_domestic_critical":0}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // Seed one city (pre-state: agri 1000 / max 2000, trust 50).
        jdbc.update(
            """
            INSERT INTO city
                (id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, trust, trade, def, def_max,
                 wall, wall_max, region, meta)
            VALUES
                (5, '성도', 5, 2, 1, 0, 50000, 100000,
                 1000, 2000, 800, 2000, 500, 1000, 50, 100, 1000, 2000,
                 1000, 2000, 1, CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
    }

    @Test
    fun `flush writes general+city+log in the databaseHooks order and rows match expected post-state`() {
        // che_농지개간 post-state (hand-built until the AREA G golden DB fragment lands):
        //  - city.agri += 100 -> 1100 (cultivation raised agriculture)
        //  - general.gold -= 100 -> 900 (paid the develop cost)
        //  - general.experience/dedication accumulate as Double (truncate -> int at flush)
        //  - general.meta gains intel_exp progress; insertion order preserved
        val postGeneral = General(
            id = 10, nationId = 2, cityId = 5,
            leadership = 70, strength = 65, intel = 80, injury = 0,
            experience = 12.0, dedication = 7.0,
            officerLevel = 4, gold = 900, rice = 1000,
            meta = linkedMapOf("explevel" to 1, "intel_exp" to 5, "max_domestic_critical" to 0),
        )
        val postCity = City(
            id = 5, nationId = 2, level = 5,
            commerce = 800, commerceMax = 2000,
            agriculture = 1100, agricultureMax = 2000,
            supplyState = 1, frontState = 0,
            trust = 50.0,
            meta = linkedMapOf(),
        )
        val logRow = LogRow(
            scope = "GENERAL", category = "ACTION",
            text = "농지를 개간하였습니다.",
            year = 190, month = 1,
            generalId = 10, nationId = 2,
            meta = linkedMapOf(),
        )

        val payload = FlushPayload(
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 2),
            updatedGenerals = listOf(postGeneral),
            updatedCities = listOf(postCity),
            logEntries = listOf(logRow),
        )

        executor.flush(payload)

        // --- op sequence == the contract (world_state, general, city, log_entry) -----------------
        assertEquals(
            listOf(
                FlushExecOp("world_state", FlushVerb.UPDATE, 1),
                FlushExecOp("general", FlushVerb.UPDATE, 1),
                FlushExecOp("city", FlushVerb.UPDATE, 1),
                FlushExecOp("log_entry", FlushVerb.CREATE_MANY, 1),
            ),
            executor.lastOps(),
        )

        // --- general row matches expected post-state (incl. meta jsonb byte string) ---------------
        val gRow = jdbc.queryForMap(
            "SELECT gold, rice, experience, dedication, meta::text AS meta FROM general WHERE id = 10",
            MapSqlParameterSource(),
        )
        assertEquals(900, intOf(gRow["gold"]))
        assertEquals(1000, intOf(gRow["rice"]))
        assertEquals(12, intOf(gRow["experience"]))
        assertEquals(7, intOf(gRow["dedication"]))
        // The `meta` column is `jsonb`, which Postgres re-renders in its own canonical form
        // (whitespace + key normalization) on read — so the textual byte-comparison is the wrong
        // oracle here (it belongs to the row mapper's encode path, covered by GeneralRowMapperTest).
        // Decode the stored jsonb and assert the logical key/value content survived the round-trip.
        assertEquals(
            mapOf<String, Any?>("explevel" to 1, "intel_exp" to 5, "max_domestic_critical" to 0),
            MetaJson.decode(stringOf(gRow["meta"])) as Map<String, Any?>,
        )

        // --- city row matches expected post-state -------------------------------------------------
        val cRow = jdbc.queryForMap(
            "SELECT agri, agri_max, trust, meta::text AS meta FROM city WHERE id = 5",
            MapSqlParameterSource(),
        )
        assertEquals(1100, intOf(cRow["agri"]))
        assertEquals(2000, intOf(cRow["agri_max"]))
        assertEquals(50, intOf(cRow["trust"]))
        assertEquals(emptyMap<String, Any?>(), MetaJson.decode(stringOf(cRow["meta"])))

        // --- world_state advanced -----------------------------------------------------------------
        val wRow = jdbc.queryForMap(
            "SELECT current_year, current_month FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
        )
        assertEquals(190, intOf(wRow["current_year"]))
        assertEquals(2, intOf(wRow["current_month"]))

        // --- exactly ONE log_entry INSERT fired ---------------------------------------------------
        val logCount = jdbc.queryForObject(
            "SELECT count(*) FROM log_entry",
            MapSqlParameterSource(),
            Int::class.java,
        )
        assertEquals(1, logCount)
        val logRowBack = jdbc.queryForMap(
            "SELECT scope::text AS scope, category::text AS category, text, year, month, general_id FROM log_entry",
            MapSqlParameterSource(),
        )
        assertEquals("GENERAL", logRowBack["scope"])
        assertEquals("ACTION", logRowBack["category"])
        assertEquals("농지를 개간하였습니다.", logRowBack["text"])
        assertEquals(190, intOf(logRowBack["year"]))
        assertEquals(1, intOf(logRowBack["month"]))
        assertEquals(10, intOf(logRowBack["general_id"]))
    }
}
