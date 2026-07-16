package opensamguk.infra.persistence

import opensamguk.infra.seed.ScenarioImporter
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
 * B1 장수생성 foundation — Testcontainers IT for the `general` create channel: step-3
 * `generalCreateMany` ([JdbcFlushExecutor.generalCreateMany]). 신규 장수 1명을 flush하면 실제 V1+V6
 * 베이스라인 스키마에 대해:
 *  - `general` 행 1개가 정확한 컬럼 값으로 INSERT되고,
 *  - `general_turn` 30행(turn_idx 0..29, 모두 휴식)이 INSERT되고,
 *  - `rank_data` 37행(RANK_COLUMNS 전체, value 0, nation_id 0)이 INSERT됨을
 * SQL로 검증한다. 이게 이 foundation의 게이트다 — executor가 방출하는 SQL이 ScenarioImporter의
 * 컬럼/행 모양과 byte-faithful 일치함을 증명한다 (장수생성/빙의/선택/건국이 소비).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GeneralCreateFlushIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var executor: JdbcFlushExecutor

    private fun ws() = linkedMapOf<String, Any?>("id" to 1, "current_year" to 200, "current_month" to 1)

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
    fun `create one brand-new general writes general + 30 general_turn + 37 rank_data`() {
        // 알려진 필드 값을 가진 신규 장수 1명 — ScenarioImporter.insertGenerals 컬럼맵과 동일 모양.
        // nation_id=0 재야, city_id=5 어딘가, officer_level=0, gold/rice=1000, crew_type_id=1100,
        // age/start_age/personal/special2/affinity/turn_time 세팅. (killturn=6은 general 컬럼이 아니라
        // meta 봉투에 — 행 컬럼 집합엔 없으므로 meta jsonb에만 들어간다.)
        val turnTime = "2026-06-07T12:00:00Z"
        val row = GeneralCreateRow(
            columns = linkedMapOf(
                "id" to 9001,
                "user_id" to "7",
                "name" to "신규장수",
                "nation_id" to 0,
                "city_id" to 5,
                "troop_id" to 0,
                "npc_state" to 0,
                "affinity" to 42,
                "born_year" to 175,
                "dead_year" to 250,
                "picture" to "default.jpg",
                "image_server" to 0,
                "leadership" to 70,
                "strength" to 65,
                "intel" to 80,
                "politics" to 73,
                "charm" to 84,
                "injury" to 0,
                "experience" to 2500,
                "dedication" to 2500,
                "officer_level" to 0,
                "gold" to 1000,
                "rice" to 1000,
                "crew" to 0,
                "crew_type_id" to 1100,
                "train" to 0,
                "atmos" to 0,
                "weapon_code" to "None",
                "book_code" to "None",
                "horse_code" to "None",
                "item_code" to "None",
                "turn_time" to turnTime,
                "age" to 25,
                "start_age" to 25,
                "personal_code" to "che_의리",
                "special_code" to "None",
                "special2_code" to "che_보병",
                "officer_city" to 0,
                "last_turn" to "{}",
                "meta" to """{"killturn":6}""",
                "penalty" to "{}",
            ),
        )

        executor.flush(FlushPayload(worldStateUpdate = ws(), createdGenerals = listOf(row)))

        // 1. general 행이 정확한 컬럼 값으로 존재.
        val g = jdbc.queryForMap(
            "SELECT * FROM general WHERE id = :id",
            MapSqlParameterSource("id", 9001),
        )
        assertEquals(9001, (g["id"] as Number).toInt())
        assertEquals("7", g["user_id"])
        assertEquals("신규장수", g["name"])
        assertEquals(0, (g["nation_id"] as Number).toInt())
        assertEquals(5, (g["city_id"] as Number).toInt())
        assertEquals(0, (g["troop_id"] as Number).toInt())
        assertEquals(0, (g["npc_state"] as Number).toInt())
        assertEquals(42, (g["affinity"] as Number).toInt())
        assertEquals(175, (g["born_year"] as Number).toInt())
        assertEquals(250, (g["dead_year"] as Number).toInt())
        assertEquals("default.jpg", g["picture"])
        assertEquals(70, (g["leadership"] as Number).toInt())
        assertEquals(65, (g["strength"] as Number).toInt())
        assertEquals(80, (g["intel"] as Number).toInt())
        assertEquals(73, (g["politics"] as Number).toInt())
        assertEquals(84, (g["charm"] as Number).toInt())
        assertEquals(2500, (g["experience"] as Number).toInt())
        assertEquals(2500, (g["dedication"] as Number).toInt())
        assertEquals(0, (g["officer_level"] as Number).toInt())
        assertEquals(1000, (g["gold"] as Number).toInt())
        assertEquals(1000, (g["rice"] as Number).toInt())
        assertEquals(1100, (g["crew_type_id"] as Number).toInt())
        assertEquals(25, (g["age"] as Number).toInt())
        assertEquals(25, (g["start_age"] as Number).toInt())
        assertEquals("che_의리", g["personal_code"])
        assertEquals("None", g["special_code"])
        assertEquals("che_보병", g["special2_code"])
        assertEquals(0, (g["officer_city"] as Number).toInt())

        // 2. general_turn 30행, 모두 휴식 (turn_idx 0..29).
        val turnCount = jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE general_id = :id",
            MapSqlParameterSource("id", 9001), Int::class.java,
        )
        assertEquals(ScenarioImporter.MAX_GENERAL_TURNS, turnCount) // 30
        val restCount = jdbc.queryForObject(
            "SELECT count(*) FROM general_turn WHERE general_id = :id AND action_code = '휴식' AND brief = '휴식'",
            MapSqlParameterSource("id", 9001), Int::class.java,
        )
        assertEquals(ScenarioImporter.MAX_GENERAL_TURNS, restCount) // 30 휴식
        val maxIdx = jdbc.queryForObject(
            "SELECT max(turn_idx) FROM general_turn WHERE general_id = :id",
            MapSqlParameterSource("id", 9001), Int::class.java,
        )
        assertEquals(29, maxIdx) // turn_idx 0..29

        // 3. rank_data 37행, value 0, nation_id 0 (RANK_COLUMNS 전체).
        val rankCount = jdbc.queryForObject(
            "SELECT count(*) FROM rank_data WHERE general_id = :id",
            MapSqlParameterSource("id", 9001), Int::class.java,
        )
        assertEquals(ScenarioImporter.RANK_COLUMNS.size, rankCount) // 37
        val rankZero = jdbc.queryForObject(
            "SELECT count(*) FROM rank_data WHERE general_id = :id AND value = 0 AND nation_id = 0",
            MapSqlParameterSource("id", 9001), Int::class.java,
        )
        assertEquals(ScenarioImporter.RANK_COLUMNS.size, rankZero) // 모두 value 0 / nation_id 0
        // 컬럼 집합이 RANK_COLUMNS와 정확히 일치(빠짐/덤 없음).
        val types = jdbc.queryForList(
            "SELECT type FROM rank_data WHERE general_id = :id",
            MapSqlParameterSource("id", 9001), String::class.java,
        ).toSet()
        assertEquals(ScenarioImporter.RANK_COLUMNS.toSet(), types)
    }
}
