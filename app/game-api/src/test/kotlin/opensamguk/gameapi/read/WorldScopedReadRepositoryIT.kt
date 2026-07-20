package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    GeneralReadRepository::class,
    NationReadRepository::class,
    CityReadRepository::class,
    GeneralTurnReadRepository::class,
    NationTurnReadRepository::class,
)
class WorldScopedReadRepositoryIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var generalTurns: GeneralTurnReadRepository
    @Autowired lateinit var nationTurns: NationTurnReadRepository

    @Test
    fun `process world returns only its own cohort rows`() {
        seedWorld(1)
        seedWorld(2)
        insertNation(id = 11, worldId = 1)
        insertNation(id = 21, worldId = 2)
        insertCity(id = 31, worldId = 1, nationId = 11)
        insertCity(id = 41, worldId = 2, nationId = 21)
        insertGeneral(id = 51, worldId = 1, nationId = 11, cityId = 31)
        insertGeneral(id = 61, worldId = 2, nationId = 21, cityId = 41)
        insertGeneralTurn(worldId = 1, generalId = 51)
        insertGeneralTurn(worldId = 2, generalId = 61)
        insertNationTurn(worldId = 1, nationId = 11)
        insertNationTurn(worldId = 2, nationId = 21)

        assertEquals(listOf(51), generals.findAll().map { it.id })
        assertTrue(generals.findById(61).isEmpty)
        assertEquals(listOf(11), nations.findAll().map { it.id })
        assertTrue(nations.findById(21).isEmpty)
        assertEquals(listOf(31), cities.findAll().map { it.id })
        assertTrue(cities.findById(41).isEmpty)
        assertEquals(listOf(51), generalTurns.findAll().map { it.generalId })
        assertTrue(generalTurns.findByGeneralIdOrderByTurnIdxAsc(61).isEmpty())
        assertEquals(listOf(11), nationTurns.findAll().map { it.nationId })
        assertTrue(nationTurns.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(21).isEmpty())
    }

    private fun seedWorld(id: Int) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, ?, 1, 1, 60)",
            id,
            "world-$id",
        )
    }

    private fun insertNation(id: Int, worldId: Int) {
        jdbc.update(
            "INSERT INTO nation (id, world_id, name, color) VALUES (?, ?, ?, '#0000ff')",
            id,
            worldId,
            "n$id",
        )
    }

    private fun insertCity(id: Int, worldId: Int, nationId: Int) {
        jdbc.update(
            """
            INSERT INTO city (
                id, world_id, name, level, nation_id, supply_state, front_state,
                pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                trust, trade, def, def_max, wall, wall_max, region, meta
            ) VALUES (?, ?, ?, 1, ?, 1, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 1, '{}'::jsonb)
            """.trimIndent(),
            id,
            worldId,
            "c$id",
            nationId,
        )
    }

    private fun insertGeneral(id: Int, worldId: Int, nationId: Int, cityId: Int) {
        jdbc.update(
            """
            INSERT INTO general (
                id, world_id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice,
                crew, crew_type_id, train, atmos, troop_id,
                weapon_code, book_code, horse_code, item_code, npc_state,
                turn_time, last_turn, meta
            ) VALUES (
                ?, ?, ?, ?, ?, 50, 50, 50, 0,
                0, 0, 1, 0, 0,
                0, 0, 0, 0, 0,
                'None', 'None', 'None', 'None', 0,
                now(), '{}'::jsonb, '{}'::jsonb
            )
            """.trimIndent(),
            id,
            worldId,
            "g$id",
            nationId,
            cityId,
        )
    }

    private fun insertGeneralTurn(worldId: Int, generalId: Int) {
        jdbc.update(
            "INSERT INTO general_turn (world_id, general_id, turn_idx, action_code, arg, brief) VALUES (?, ?, 0, '휴식', '{}'::jsonb, '휴식')",
            worldId,
            generalId,
        )
    }

    private fun insertNationTurn(worldId: Int, nationId: Int) {
        jdbc.update(
            "INSERT INTO nation_turn (world_id, nation_id, officer_level, turn_idx, action_code, arg, brief) VALUES (?, ?, 5, 0, '휴식', '{}'::jsonb, '휴식')",
            worldId,
            nationId,
        )
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
