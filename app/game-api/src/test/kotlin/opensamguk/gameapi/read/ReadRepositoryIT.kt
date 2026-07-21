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
import kotlin.test.assertNull
import kotlin.test.assertTrue
/**
 * Task E1 — proves the precheck JPA READ repos materialize the V1 baseline rows into the shared
 * `:logic` entities. Testcontainers `postgres:16-alpine` + the `:infra` Flyway baseline (on the
 * test classpath through the `:infra` dependency) applied via `ddl-auto: validate`.
 *
 * Seeds rows with raw SQL (jsonb-cast `meta`), loads through the Spring Data repos, and asserts:
 * - the column subset materializes;
 * - `intel_exp`/`explevel`/`max_domestic_critical` come from `meta` (NO such column on the entity);
 * - `city.trust` (INTEGER baseline column) widens to the logic `Double`.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    WorldStateReadRepository::class,
    DiplomacyReadRepository::class,
    GeneralReadRepository::class,
    CityReadRepository::class,
    NationReadRepository::class,
)
class ReadRepositoryIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var diplomacies: DiplomacyReadRepository
    @Autowired lateinit var worldStates: WorldStateReadRepository

    private fun seedWorld() {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config, meta) VALUES (1, 'scenario_2', 195, 3, 3600, '{\"startYear\":180}'::jsonb, '{}'::jsonb)",
        )
    }

    @Test
    fun `read entities materialize from the baseline rows`() {
        // world_state singleton
        seedWorld()
        // nation (level 7, with a capital); gennum/capset + tech ride the full P2 shape
        jdbc.update(
            """
            INSERT INTO nation (id, world_id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta)
            VALUES (1, 1, '위', '#0000ff', 5, 12000, 9000, 1234.5, 7, 'che_명사',
                    '{"gennum":24,"capset":3,"rate":15}'::jsonb)
            """.trimIndent()
        )
        jdbc.update(
            "INSERT INTO nation (id, world_id, name, color) VALUES (2, 1, '촉', '#00ff00')",
        )
        // a directional diplomacy row: nation 1 -> nation 2, state 2 (불가침), term 6 months
        jdbc.update(
            """
            INSERT INTO diplomacy (world_id, src_nation_id, dest_nation_id, state_code, term)
            VALUES (1, 1, 2, 2, 6)
            """.trimIndent()
        )
        // city owned by nation 1; trust is the INTEGER baseline column (=82) -> widens to 82.0
        jdbc.update(
            """
            INSERT INTO city (
                id, world_id, name, level, nation_id, supply_state, front_state,
                pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                trust, trade, def, def_max, wall, wall_max, region, meta
            ) VALUES (
                5, 1, '허창', 5, 1, 1, 0,
                50000, 100000, 4000, 8000, 3000, 8000, 1000, 2000,
                82, 100, 500, 1000, 800, 1500, 3, '{}'::jsonb
            )
            """.trimIndent()
        )
        // general in city 5 / nation 1; intel_exp/explevel/max_domestic_critical live in meta only.
        // full P2 military/equip surface + a 4-field last_turn jsonb.
        jdbc.update(
            """
            INSERT INTO general (
                id, world_id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice,
                crew, crew_type_id, train, atmos, troop_id,
                weapon_code, book_code, horse_code, item_code, npc_state,
                turn_time, last_turn, meta
            ) VALUES (
                10, 1, '순욱', 1, 5, 70, 30, 95, 0,
                1200, 900, 5, 4000, 3000,
                500, 3, 80, 90, 7,
                '방천화극', 'None', '여포의적토마', 'None', 2,
                now(),
                '{"command":"che_이동","arg":{"destCityID":12},"term":3,"seq":1}'::jsonb,
                '{"explevel":4,"intel_exp":12,"max_domestic_critical":3.5}'::jsonb
            )
            """.trimIndent()
        )
        // --- world_state ---
        val ws = worldStates.findAll().single()
        assertEquals("scenario_2", ws.scenarioCode)
        assertEquals(195, ws.currentYear)
        assertEquals(3, ws.currentMonth)
        assertEquals(180, (ws.config["startYear"] as Number).toInt())
        // --- nation: full P2 shape; gennum/capset read back FROM meta (no dedicated columns) ---
        val nation = nations.findById(1).orElseThrow().toLogic()
        assertEquals(1, nation.id)
        assertEquals(7, nation.level)
        assertEquals(5, nation.capitalCityId)
        assertEquals("위", nation.name)
        assertEquals("#0000ff", nation.color)
        assertEquals("che_명사", nation.typeCode)
        assertEquals(12000, nation.gold)
        assertEquals(9000, nation.rice)
        assertEquals(1234.5, nation.tech)
        assertEquals(24, nation.gennum)
        assertEquals(3, nation.capset)
        // --- diplomacy: directional row materializes into the logic Diplomacy via the read repo ---
        val diplo = diplomacies.findBySrcNationId(1).map { it.toLogic() }
        assertEquals(1, diplo.size)
        assertEquals(1, diplo[0].me)
        assertEquals(2, diplo[0].you)
        assertEquals(2, diplo[0].state)
        assertEquals(6, diplo[0].term)
        // --- city: int trust widens to Double; P2 develop/defense surface materializes ---
        val city = cities.findById(5).orElseThrow().toLogic()
        assertEquals(5, city.id)
        assertEquals(1, city.nationId)
        assertEquals(4000, city.agriculture)
        assertEquals(8000, city.agricultureMax)
        assertEquals(3000, city.commerce)
        assertEquals(82.0, city.trust) // INTEGER baseline column widened to logic Double
        assertEquals(1, city.supplyState)
        assertEquals(1000, city.security)
        assertEquals(2000, city.securityMax)
        assertEquals(500, city.defense)
        assertEquals(1000, city.defenseMax)
        assertEquals(800, city.wall)
        assertEquals(1500, city.wallMax)
        assertEquals(50000, city.population)
        assertEquals(100000, city.populationMax)
        assertEquals(100, city.trade)
        assertEquals(3, city.region)
        // --- general: meta carries intel_exp/explevel/max_domestic_critical (NO entity column) ---
        val general = generals.findById(10).orElseThrow().toLogic()
        assertEquals(10, general.id)
        assertEquals(1, general.nationId)
        assertEquals(5, general.cityId)
        assertEquals(95, general.intel)
        assertEquals(4000, general.gold)
        assertEquals(1200.0, general.experience) // int column widened to Double accumulator
        assertEquals(900.0, general.dedication)
        // P2 military/equip surface
        assertEquals(500, general.crew)
        assertEquals(3, general.crewTypeId)
        assertEquals(80.0, general.train)        // int column widened to Double
        assertEquals(90.0, general.atmos)
        assertEquals(7, general.troop)
        assertEquals("방천화극", general.weapon)
        assertEquals("여포의적토마", general.horse)
        assertEquals("None", general.book)
        assertEquals("None", general.item)
        assertEquals(2, general.npcType)
        // last_turn jsonb materializes into the 4-field LastTurn
        assertEquals("che_이동", general.lastTurn.command)
        assertEquals(mapOf("destCityID" to 12), general.lastTurn.arg)
        assertEquals(3, general.lastTurn.term)
        assertEquals(1, general.lastTurn.seq)
        // meta-resident exp/level fields
        assertEquals(4, (general.meta["explevel"] as Number).toInt())
        assertEquals(12, (general.meta["intel_exp"] as Number).toInt())
        assertEquals(3.5, (general.meta["max_domestic_critical"] as Number).toDouble())
        assertNull(general.meta["nonexistent"])
        assertTrue(general.meta.keys.toList() == listOf("explevel", "intel_exp", "max_domestic_critical"))
    }
    /**
     * W9 (9a) T0 — `findDistinctCityIdByNationId`(`getWorldMap`의 shownByGeneralList 원천). 같은 도시의
     * 여러 장수는 1번만, cityId-ascending 정렬. 다른 국가/같은 도시는 제외.
     */
    @Test
    fun `distinct city ids of a nation are deduped and ascending`() {
        seedWorld()
        fun insertGeneral(id: Int, nationId: Int, cityId: Int) {
            jdbc.update(
                """
                INSERT INTO general (
                    id, world_id, name, nation_id, city_id, leadership, strength, intel, injury,
                    experience, dedication, officer_level, gold, rice,
                    crew, crew_type_id, train, atmos, troop_id,
                    weapon_code, book_code, horse_code, item_code, npc_state,
                    turn_time, last_turn, meta
                ) VALUES (
                    $id, 1, 'g$id', $nationId, $cityId, 50, 50, 50, 0,
                    0, 0, 1, 0, 0,
                    0, 0, 0, 0, 0,
                    'None', 'None', 'None', 'None', 0,
                    now(), '{}'::jsonb, '{}'::jsonb
                )
                """.trimIndent(),
            )
        }
        // nation 1: city 5 (x2 → dedup), city 12, city 3 → distinct asc = [3, 5, 12]
        insertGeneral(20, 1, 5)
        insertGeneral(21, 1, 5)
        insertGeneral(22, 1, 12)
        insertGeneral(23, 1, 3)
        // nation 2 in city 3 → must NOT leak into nation 1's set
        insertGeneral(24, 2, 3)
        val cityIds = generals.findDistinctCityIdByNationId(1)
        assertEquals(listOf(3, 5, 12), cityIds)
        assertEquals(listOf(3), generals.findDistinctCityIdByNationId(2))
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
