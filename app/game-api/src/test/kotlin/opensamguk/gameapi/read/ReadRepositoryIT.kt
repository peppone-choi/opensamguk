package opensamguk.gameapi.read

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
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
@Testcontainers
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReadRepositoryIT {

    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var worldStates: WorldStateReadRepository

    @Test
    fun `read entities materialize from the baseline rows`() {
        // world_state singleton
        jdbc.update(
            """
            INSERT INTO world_state (scenario_code, current_year, current_month, tick_seconds, config, meta)
            VALUES ('scenario_2', 195, 3, 3600, '{"startYear":180}'::jsonb, '{}'::jsonb)
            """.trimIndent()
        )
        // nation (level 7, with a capital)
        jdbc.update(
            """
            INSERT INTO nation (id, name, color, capital_city_id, level, type_code)
            VALUES (1, '위', '#0000ff', 5, 7, 'che_명사')
            """.trimIndent()
        )
        // city owned by nation 1; trust is the INTEGER baseline column (=82) -> widens to 82.0
        jdbc.update(
            """
            INSERT INTO city (
                id, name, level, nation_id, supply_state, front_state,
                pop, pop_max, agri, agri_max, comm, comm_max, secu, secu_max,
                trust, trade, def, def_max, wall, wall_max, region, meta
            ) VALUES (
                5, '허창', 5, 1, 1, 0,
                50000, 100000, 4000, 8000, 3000, 8000, 1000, 2000,
                82, 100, 500, 1000, 800, 1500, 3, '{}'::jsonb
            )
            """.trimIndent()
        )
        // general in city 5 / nation 1; intel_exp/explevel/max_domestic_critical live in meta only
        jdbc.update(
            """
            INSERT INTO general (
                id, name, nation_id, city_id, leadership, strength, intel, injury,
                experience, dedication, officer_level, gold, rice, turn_time, meta
            ) VALUES (
                10, '순욱', 1, 5, 70, 30, 95, 0,
                1200, 900, 5, 4000, 3000, now(),
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

        // --- nation ---
        val nation = nations.findById(1).orElseThrow().toLogic()
        assertEquals(1, nation.id)
        assertEquals(7, nation.level)
        assertEquals(5, nation.capitalCityId)

        // --- city: int trust widens to Double; agri/comm align to baseline columns ---
        val city = cities.findById(5).orElseThrow().toLogic()
        assertEquals(5, city.id)
        assertEquals(1, city.nationId)
        assertEquals(4000, city.agriculture)
        assertEquals(8000, city.agricultureMax)
        assertEquals(3000, city.commerce)
        assertEquals(82.0, city.trust) // INTEGER baseline column widened to logic Double
        assertEquals(1, city.supplyState)

        // --- general: meta carries intel_exp/explevel/max_domestic_critical (NO entity column) ---
        val general = generals.findById(10).orElseThrow().toLogic()
        assertEquals(10, general.id)
        assertEquals(1, general.nationId)
        assertEquals(5, general.cityId)
        assertEquals(95, general.intel)
        assertEquals(4000, general.gold)
        assertEquals(1200.0, general.experience) // int column widened to Double accumulator
        assertEquals(900.0, general.dedication)
        assertEquals(4, (general.meta["explevel"] as Number).toInt())
        assertEquals(12, (general.meta["intel_exp"] as Number).toInt())
        assertEquals(3.5, (general.meta["max_domestic_critical"] as Number).toDouble())
        assertNull(general.meta["nonexistent"])
        assertTrue(general.meta.keys.toList() == listOf("explevel", "intel_exp", "max_domestic_critical"))
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
