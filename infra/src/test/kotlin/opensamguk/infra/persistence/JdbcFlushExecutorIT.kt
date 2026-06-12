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
        //  - general.experience/dedication accumulate as Double (ROUND half-away-from-zero -> int at flush)
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
            // the widened step-7 city UPDATE (FF2) now writes the develop/defense columns too —
            // preserve the seeded pre-state values (the agri-cultivation post-state changes only agri).
            security = 500, securityMax = 1000,
            defense = 1000, defenseMax = 2000,
            wall = 1000, wallMax = 2000,
            population = 50000, populationMax = 100000,
            trade = 100, region = 1,
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

    /**
     * prod 월틱 동결 3차의 real-DB 회귀 가드. 엔진의 global history 로그(scope 문자열 "global")는
     * `DatabaseHooks.toLogRow`에서 PG enum 리터럴 `SYSTEM`으로 번역돼야 한다 — 직전엔 단순 uppercase로
     * `"GLOBAL"`이 돼 log_scope enum(SYSTEM/NATION/GENERAL/USER)에 없는 값 → 이 INSERT가
     * `BatchUpdateException: invalid input value for enum log_scope: "GLOBAL"`로 터지고 틱이 롤백돼
     * 턴이 0진행이었다. 여기서는 SYSTEM/HISTORY 리터럴이 실제 Postgres enum에 INSERT됨을 검증한다.
     * (다른 테스트의 `log_entry count==1` 단언을 위해 끝에서 자기 행을 정리한다.)
     */
    @Test
    fun `SYSTEM (global) scope history log flushes against real Postgres (the GLOBAL enum crash)`() {
        val sysLog = LogRow(
            scope = "SYSTEM", category = "HISTORY",
            text = "봄이 되어 봉록에 따라 자금이 지급됩니다.",
            year = 190, month = 1,
            generalId = null, nationId = null,
            meta = linkedMapOf(),
        )
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 1),
                logEntries = listOf(sysLog),
            ),
        )

        val back = jdbc.queryForMap(
            "SELECT scope::text AS scope, category::text AS category, text FROM log_entry " +
                "WHERE scope = CAST('SYSTEM' AS log_scope)",
            MapSqlParameterSource(),
        )
        assertEquals("SYSTEM", back["scope"])
        assertEquals("HISTORY", back["category"])
        assertEquals("봄이 되어 봉록에 따라 자금이 지급됩니다.", back["text"])

        // cleanup — 다른 테스트의 'log_entry 정확히 1개' 단언 보존.
        jdbc.update(
            "DELETE FROM log_entry WHERE scope = CAST('SYSTEM' AS log_scope)",
            MapSqlParameterSource(),
        )
    }

    /**
     * lastTurnTime 영속화 회귀 가드 — 이 키가 flush 되지 않으면 WorldSnapshotLoader 가 부팅 시
     * start_time 폴백으로 떨어져 **월드 시작부터 전 월을 재생**한다(2026-06-12 s1 프로드 실증:
     * 엔진 재기동 → 19개월 이중 적용 + 로그 중복 INSERT). 기존 meta 키 보존(병합)도 같이 핀.
     */
    @Test
    fun `world_state flush persists lastTurnTime into meta and preserves existing keys`() {
        // pre: 기존 meta 키 심기 (startTime/startYear 류 보존 검증용).
        jdbc.update(
            "UPDATE world_state SET meta = CAST('{\"startYear\":181,\"hiddenSeed\":\"hs\"}' AS jsonb) WHERE id = 1",
            MapSqlParameterSource(),
        )

        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf(
                    "id" to 1, "current_year" to 190, "current_month" to 3,
                    "last_turn_time" to "2026-06-12T00:30:00Z",
                ),
            ),
        )

        val meta = jdbc.queryForObject(
            "SELECT meta::text FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            String::class.java,
        )!!
        assertEquals(true, meta.contains("\"lastTurnTime\": \"2026-06-12T00:30:00Z\"") || meta.contains("\"lastTurnTime\":\"2026-06-12T00:30:00Z\""))
        assertEquals(true, meta.contains("\"startYear\": 181") || meta.contains("\"startYear\":181"))
        assertEquals(true, meta.contains("hiddenSeed"))

        // last_turn_time 미포함 페이로드는 meta 를 건드리지 않는다(키 유지).
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 4),
            ),
        )
        val meta2 = jdbc.queryForObject(
            "SELECT meta::text FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            String::class.java,
        )!!
        assertEquals(true, meta2.contains("lastTurnTime"))

        // cleanup — 다른 테스트와의 격리(meta 원복).
        jdbc.update(
            "UPDATE world_state SET meta = CAST('{}' AS jsonb), current_year = 190, current_month = 1 WHERE id = 1",
            MapSqlParameterSource(),
        )
    }
}
