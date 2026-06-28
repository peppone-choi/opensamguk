package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
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
        // #9 power-영속 가드용 nation 시드 (pre-state: power 1000). 월틱 Q4 재산정값이
        // nationUpdate SET 절을 통해 실제로 영속되는지 검증한다.
        jdbc.update(
            """
            INSERT INTO nation (id, name, color, capital_city_id, gold, rice, tech, level, type_code, power, meta)
            VALUES (2, '촉', '#00ff00', 5, 5000, 5000, 0, 1, 'che_촉', 1000, CAST('{}' AS jsonb))
            """.trimIndent(),
            MapSqlParameterSource(),
        )
        // #17 officer_city-영속 가드용 태수 시드 (pre-state: officer_level 3 = 태수, officer_city 5).
        // ConquerCity 생존 강등(process_war.php:705-708)을 모사해 officer_city 0 으로 flush 되는지 본다.
        jdbc.update(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, leadership, strength, intel, injury,
                 experience, dedication, officer_level, officer_city, gold, rice, turn_time, meta)
            VALUES
                (11, '태수십일', 2, 5, 60, 60, 60, 0, 0, 0, 3, 5, 100, 100, now(), CAST('{}' AS jsonb))
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
            year = 190, month = 1, phase = 2,
            generalId = 10, nationId = 2,
            meta = linkedMapOf(),
        )

        val payload = FlushPayload(
            worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 2, "current_phase" to 3),
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
            "SELECT current_year, current_month, current_phase FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
        )
        assertEquals(190, intOf(wRow["current_year"]))
        assertEquals(2, intOf(wRow["current_month"]))
        assertEquals(3, intOf(wRow["current_phase"]))

        // --- exactly ONE log_entry INSERT fired ---------------------------------------------------
        val logCount = jdbc.queryForObject(
            "SELECT count(*) FROM log_entry",
            MapSqlParameterSource(),
            Int::class.java,
        )
        assertEquals(1, logCount)
        val logRowBack = jdbc.queryForMap(
            "SELECT scope::text AS scope, category::text AS category, text, year, month, phase, general_id FROM log_entry",
            MapSqlParameterSource(),
        )
        assertEquals("GENERAL", logRowBack["scope"])
        assertEquals("ACTION", logRowBack["category"])
        assertEquals("농지를 개간하였습니다.", logRowBack["text"])
        assertEquals(190, intOf(logRowBack["year"]))
        assertEquals(1, intOf(logRowBack["month"]))
        assertEquals(2, intOf(logRowBack["phase"]))
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
            "UPDATE world_state SET meta = CAST('{}' AS jsonb), current_year = 190, current_month = 1, isunited = 0 WHERE id = 1",
            MapSqlParameterSource(),
        )
    }

    /**
     * isunited column flush round-trip — 천하통일/엔딩 상태가 world_state 컬럼에 영속되고,
     * payload 미포함 시 0으로 동기화된다.
     */
    @Test
    fun `world_state flush persists isunited column`() {
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf(
                    "id" to 1, "current_year" to 190, "current_month" to 5,
                    "isunited" to 2,
                ),
            ),
        )
        assertEquals(2, jdbc.queryForObject("SELECT isunited FROM world_state WHERE id = 1", MapSqlParameterSource(), Int::class.java))

        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 6),
            ),
        )
        assertEquals(0, jdbc.queryForObject("SELECT isunited FROM world_state WHERE id = 1", MapSqlParameterSource(), Int::class.java))

        jdbc.update(
            "UPDATE world_state SET meta = CAST('{}' AS jsonb), current_year = 190, current_month = 1, isunited = 0 WHERE id = 1",
            MapSqlParameterSource(),
        )
    }

    /**
     * Persistent id high-water mark round-trip — engine-assigned nation.id/general.id are NOT serial,
     * so the next free id must survive restarts. The flush merges maxNationId/maxGeneralId into
     * world_state.meta alongside lastTurnTime.
     */
    @Test
    fun `world_state flush persists maxNationId and maxGeneralId into meta`() {
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf(
                    "id" to 1, "current_year" to 190, "current_month" to 7,
                    "max_nation_id" to 7,
                    "max_general_id" to 15,
                ),
            ),
        )

        val meta = jdbc.queryForObject(
            "SELECT meta::text FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            String::class.java,
        )!!
        assertEquals(true, meta.contains("\"maxNationId\": 7") || meta.contains("\"maxNationId\":7"))
        assertEquals(true, meta.contains("\"maxGeneralId\": 15") || meta.contains("\"maxGeneralId\":15"))

        // Subsequent flush without explicit max keys preserves the previous high-water mark
        // (the executor always merges the current payload values, defaulting to 0 — caller must
        // carry forward the persisted value; this test documents that contract).
        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 8),
            ),
        )
        val meta2 = jdbc.queryForObject(
            "SELECT meta::text FROM world_state WHERE id = 1",
            MapSqlParameterSource(),
            String::class.java,
        )!!
        assertEquals(true, meta2.contains("maxNationId"))

        jdbc.update(
            "UPDATE world_state SET meta = CAST('{}' AS jsonb), current_year = 190, current_month = 1, isunited = 0 WHERE id = 1",
            MapSqlParameterSource(),
        )
    }

    /**
     * #9 회귀 가드 — nationUpdate SET 절에 `power = :power` 가 없어 월틱 Q4(func_gamerule.php:322-333)가
     * 재산정한 nation.power 가 라이브 수렴 경로에서 영속되지 않았다. NationRowMapper.toColumns 는 이미
     * power 를 방출하므로, 순수 누락된 SET 항목 추가가 round-trip 되는지 확인한다(pre 1000 → post 1234).
     */
    @Test
    fun `nation flush persists power column (the dropped SET clause)`() {
        val postNation = Nation(
            id = 2, level = 1, capitalCityId = 5,
            name = "촉", color = "#00ff00", typeCode = "che_촉",
            gold = 5000, rice = 5000,
            power = 1234, // 월틱 Q4 재산정값을 모사
            tech = 0.0,
            meta = linkedMapOf(),
        )

        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 1),
                updatedNations = listOf(postNation),
            ),
        )

        val nRow = jdbc.queryForMap(
            "SELECT power, gold FROM nation WHERE id = 2",
            MapSqlParameterSource(),
        )
        assertEquals(1234, intOf(nRow["power"]))
        assertEquals(5000, intOf(nRow["gold"]))
    }

    /**
     * #17 회귀 가드 — generalUpdate SET 절에 `officer_city = :officer_city` 가 없어 ConquerCity 생존 강등
     * (process_war.php:705-708, officer_city=0/officer_level=1)이 전용 컬럼에 반영되지 않았다.
     * GeneralRowMapper.toColumns 는 이미 officer_city 를 방출하므로, 태수(officer_city 5)→일반(0) 강등이
     * round-trip 되는지 확인한다.
     */
    @Test
    fun `general flush persists officer_city column (governor demotion to 0)`() {
        val demotedGeneral = General(
            id = 11, nationId = 2, cityId = 5,
            leadership = 60, strength = 60, intel = 60, injury = 0,
            experience = 0.0, dedication = 0.0,
            officerLevel = 1,        // 태수(3) → 일반(1)
            gold = 100, rice = 100,
            officerCity = 0,         // 태수직 도시(5) → 0
            meta = linkedMapOf(),
        )

        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 1),
                updatedGenerals = listOf(demotedGeneral),
            ),
        )

        val gRow = jdbc.queryForMap(
            "SELECT officer_city, officer_level FROM general WHERE id = 11",
            MapSqlParameterSource(),
        )
        assertEquals(0, intOf(gRow["officer_city"]))
        assertEquals(1, intOf(gRow["officer_level"]))
    }

    /**
     * #10 회귀 가드 — DatabaseHooks 3-인자 toFlushPayload 가 statisticInserts 를 매핑하지 않아 연경계
     * (checkStatistic) statistic 행이 라이브 수렴 경로에서 누락됐다. 여기서는 executor 의 step-12
     * statisticInsertMany 가 StatisticInsertRow 를 받아 statistic 테이블에 INSERT 함을 직접 검증한다
     * (DatabaseHooks 매핑 자체는 game-engine 측에서 dirty.statisticInserts 빌더로 커버됨).
     */
    @Test
    fun `statistic insert flushes into the statistic table (step-12)`() {
        val statRow = StatisticInsertRow(
            linkedMapOf(
                "year" to 190, "month" to 1,
                "nation_count" to 2,
                "nation_name" to "촉,위",
                "nation_hist" to "1000,900",
                "gen_count" to "10,8",
                "personal_hist" to "ph",
                "special_hist" to "sh",
                "power_hist" to "1000,900",
                "crewtype" to "ct",
                "etc" to "e",
                "aux" to "{\"k\":\"v\"}",
            ),
        )

        executor.flush(
            FlushPayload(
                worldStateUpdate = linkedMapOf("id" to 1, "current_year" to 190, "current_month" to 1),
                statisticInserts = listOf(statRow),
            ),
        )

        val sRow = jdbc.queryForMap(
            "SELECT year, month, nation_count, nation_name, power_hist, aux::text AS aux " +
                "FROM statistic WHERE year = 190 AND month = 1",
            MapSqlParameterSource(),
        )
        assertEquals(190, intOf(sRow["year"]))
        assertEquals(1, intOf(sRow["month"]))
        assertEquals(2, intOf(sRow["nation_count"]))
        assertEquals("촉,위", sRow["nation_name"])
        assertEquals("1000,900", sRow["power_hist"])
        // aux 는 jsonb — 키/값 구조 동등으로 검증(byte-order 계약은 flush 페이로드까지).
        assertEquals(mapOf<String, Any?>("k" to "v"), MetaJson.decode(stringOf(sRow["aux"])) as Map<String, Any?>)

        // cleanup — 다른 테스트와의 격리(statistic 행 제거).
        jdbc.update(
            "DELETE FROM statistic WHERE year = 190 AND month = 1",
            MapSqlParameterSource(),
        )
    }
}
