package opensamguk.gameapi.read
import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
/**
 * W3 Wave-0 FOUNDATION IT — F1(GeneralReadEntity 신규 컬럼 매핑) + F2(RankDataReadRepository) +
 * F4(NationReadRepository 집계)가 V1/V6/V8 baseline 행을 정확히 materialize함을 증명한다.
 *
 * `ReadRepositoryIT`와 동일한 Testcontainers `postgres:16-alpine` + `:infra` Flyway baseline
 * (`ddl-auto: validate`) 패턴. raw SQL로 행을 심고 read 레포로 로드해 단언한다.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    GeneralReadRepository::class,
    NationReadRepository::class,
    NationTurnReadRepository::class,
)
class W3FoundationReadIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var rankData: RankDataReadRepository
    @Autowired lateinit var nationTurns: NationTurnReadRepository

    private fun seedWorld() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (1, 'test', 1, 1, 60)",
        )
    }

    /** general 행 1개를 신규 컬럼(special/personal/penalty/officer_city/turn_time)까지 포함해 심는다. */
    private fun insertGeneral(
        id: Int,
        nationId: Int,
        cityId: Int,
        crew: Int,
        leadership: Int,
        npc: Int,
        special: String = "None",
        special2: String = "None",
        personal: String = "None",
        penaltyJson: String = "{}",
        officerCity: Int = 0,
    ) {
        jdbc.update(
            """
            INSERT INTO general (
                id, world_id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice,
                crew, crew_type_id, train, atmos, troop_id,
                weapon_code, book_code, horse_code, item_code, npc_state,
                special_code, special2_code, personal_code, penalty, officer_city,
                turn_time, last_turn, meta
            ) VALUES (
                $id, 1, 'g$id', $nationId, $cityId, $leadership, 50, 50, 0,
                0, 0, 1, 0, 0,
                $crew, 0, 0, 0, 0,
                'None', 'None', 'None', 'None', $npc,
                '$special', '$special2', '$personal', '$penaltyJson'::jsonb, $officerCity,
                TIMESTAMPTZ '2026-06-03 10:30:45+00', '{}'::jsonb, '{}'::jsonb
            )
            """.trimIndent(),
        )
    }
    private fun insertCity(id: Int, nationId: Int, pop: Int, popMax: Int) {
        jdbc.update(
            """
            INSERT INTO city (
                id, world_id, name, level, nation_id, supply_state, front_state,
                pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                trust, trade, def, def_max, wall, wall_max, region, meta
            ) VALUES (
                $id, 1, 'c$id', 5, $nationId, 1, 0,
                $pop, $popMax, 0, 0, 0, 0, 0, 0,
                0, 100, 0, 0, 0, 0, 1, '{}'::jsonb
            )
            """.trimIndent(),
        )
    }
    @Test
    fun `F1 - general 신규 컬럼이 baseline 행에서 materialize 된다`() {
        seedWorld()
        insertGeneral(
            id = 10, nationId = 1, cityId = 5, crew = 500, leadership = 70, npc = 2,
            special = "급습", special2 = "보병", personal = "용맹",
            penaltyJson = """{"betray":2}""", officerCity = 5,
        )
        val g = generals.findById(10).orElseThrow()
        assertEquals("급습", g.specialCode)
        assertEquals("보병", g.special2Code)
        assertEquals("용맹", g.personalCode)
        assertEquals(2, (g.penalty["betray"] as Number).toInt()) // penalty는 V1 jsonb 컬럼
        assertEquals(5, g.officerCity)                            // V6 officer_city
        assertNotNull(g.turnTime)
        // F3 포맷터가 이 turn_time을 TURNTIME_FULL로 변환.
        assertEquals("2026-06-03 19:30:45", TurnTimeFormatter.full(g.turnTime))
    }
    @Test
    fun `F2 - rank_data 전투 통계 read 경로가 동작한다`() {
        seedWorld()
        insertGeneral(id = 11, nationId = 1, cityId = 5, crew = 0, leadership = 50, npc = 0)
        // RankColumn backing value와 byte-동일한 type 문자열.
        for ((type, value) in listOf(
            "warnum" to 12, "killnum" to 30, "deathnum" to 4,
            "killcrew" to 5000, "deathcrew" to 800, "firenum" to 7,
        )) {
            jdbc.update(
                "INSERT INTO rank_data (world_id, nation_id, general_id, type, value) VALUES (1, 1, 11, ?, ?)",
                type, value,
            )
        }
        val rows = rankData.findByGeneralId(11)
        assertEquals(6, rows.size)
        val byType = rows.associate { it.type to it.value }
        assertEquals(12, byType["warnum"])
        assertEquals(5000, byType["killcrew"])
        // 단건 조회
        assertEquals(30, rankData.findByGeneralIdAndType(11, "killnum")?.value)
        assertNull(rankData.findByGeneralIdAndType(11, "occupied")) // 미기록 type → null
        // N+1-safe 일괄 조회: 장수 집합 × 전투-통계 type 집합
        insertGeneral(id = 12, nationId = 1, cityId = 5, crew = 0, leadership = 50, npc = 0)
        jdbc.update("INSERT INTO rank_data (world_id, nation_id, general_id, type, value) VALUES (1, 1, 12, 'warnum', 99)")
        val warTypes = listOf("warnum", "killnum", "deathnum", "killcrew", "deathcrew", "firenum")
        val bulk = rankData.findByGeneralIdsAndTypes(listOf(11, 12), warTypes)
        val folded = bulk.associate { (it.generalId to it.type) to it.value }
        assertEquals(12, folded[11 to "warnum"])
        assertEquals(99, folded[12 to "warnum"])
    }
    @Test
    fun `F4 - 국가별 인구-병력 집계가 단일 grouped query로 나온다`() {
        seedWorld()
        // nation 1: city 5(pop 50000/100000) + city 6(pop 30000/60000) → cityCnt 2, now 80000, max 160000
        insertCity(5, 1, 50000, 100000)
        insertCity(6, 1, 30000, 60000)
        // nation 1 장수: g30(crew 500, lead 70, npc 0) + g31(crew 300, lead 50, npc 0)
        //   → generalCnt 2, now 800, max (70+50)*100 = 12000
        insertGeneral(id = 30, nationId = 1, cityId = 5, crew = 500, leadership = 70, npc = 0)
        insertGeneral(id = 31, nationId = 1, cityId = 6, crew = 300, leadership = 50, npc = 0)
        // npc_state=5 장수는 crew 집계에서 제외되어야 한다.
        insertGeneral(id = 32, nationId = 1, cityId = 5, crew = 9999, leadership = 99, npc = 5)
        val pops = nations.aggregatePopulationByNation().associateBy { it.nationId }
        val p1 = pops[1]
        assertNotNull(p1)
        assertEquals(2L, p1.cityCnt)
        assertEquals(80000L, p1.now)
        assertEquals(160000L, p1.max)
        val crews = nations.aggregateCrewByNation().associateBy { it.nationId }
        val c1 = crews[1]
        assertNotNull(c1)
        assertEquals(2L, c1.generalCnt)        // npc=5 제외
        assertEquals(800L, c1.now)             // 500+300, npc=5의 9999 제외
        assertEquals(12000L, c1.max)           // (70+50)*100, npc=5의 99 제외
        // 단일 국가 오버로드도 동일 값.
        val singleP = nations.aggregatePopulationOfNation(1)
        assertEquals(80000L, singleP?.now)
        val singleC = nations.aggregateCrewOfNation(1)
        assertEquals(800L, singleC?.now)
        // 도시/장수가 전혀 없는 국가는 빈 결과(null).
        assertNull(nations.aggregatePopulationOfNation(999))
        assertNull(nations.aggregateCrewOfNation(999))
    }
    @Test
    fun `ChiefCenter - nation_turn arg jsonb가 read 경로에서 디코드된다`() {
        seedWorld()
        jdbc.update("INSERT INTO nation (world_id, id, name, color) VALUES (1, 1, '테스트국', '#000000')")
        // V1 baseline nation_turn: 예약 국가 명령 슬롯(arg jsonb). PHP가 슬롯마다 Json::decode($arg)로
        // 내려보내던 누락 필드를 read 엔티티가 삽입순서 맵으로 디코드함을 증명.
        jdbc.update(
            """
            INSERT INTO nation_turn (world_id, nation_id, officer_level, turn_idx, action_code, arg, brief)
            VALUES (1, 1, 12, 0, 'che_증축', '{"destCityID":5}'::jsonb, '증축'),
                   (1, 1, 12, 1, '휴식', '{}'::jsonb, '휴식')
            """.trimIndent(),
        )
        val rows = nationTurns.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(1)
        assertEquals(2, rows.size)
        val slot0 = rows.first { it.turnIdx == 0 }
        assertEquals("che_증축", slot0.actionCode)
        assertEquals("증축", slot0.brief)
        assertEquals(5, (slot0.arg["destCityID"] as Number).toInt()) // W3 — 누락됐던 arg 디코드
        // 인자 없는 슬롯은 빈 맵.
        val slot1 = rows.first { it.turnIdx == 1 }
        assertEquals(0, slot1.arg.size)
    }
    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
