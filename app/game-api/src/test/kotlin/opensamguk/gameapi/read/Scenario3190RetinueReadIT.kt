package opensamguk.gameapi.read

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityManager
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorldIdConfiguration
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.ScenarioImporter
import opensamguk.infra.seed.ScenarioJson
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownRules
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
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Packaged 3190 -> isolated PostgreSQL -> real game-api read repositories -> CampReader.
 * SQL assigns ownership only: not select-npc possession, HTTP routing, GUI or live turn QA.
 * Clearing the persistence context forces DB reloads; no engine assessment or synthetic ranking KV.
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    GameApiProcessWorldIdConfiguration::class,
    GeneralReadRepository::class, WorldStateReadRepository::class, NationReadRepository::class,
    CityReadRepository::class, RetainerReadRepository::class, GameKvReadRepository::class,
    WorldArtifactIdentityReadRepository::class,
)
class Scenario3190RetinueReadIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var entityManager: EntityManager
    @Autowired lateinit var generals: GeneralReadRepository
    @Autowired lateinit var worlds: WorldStateReadRepository
    @Autowired lateinit var nations: NationReadRepository
    @Autowired lateinit var cities: CityReadRepository
    @Autowired lateinit var retainers: RetainerReadRepository
    @Autowired lateinit var gameKv: GameKvReadRepository
    @Autowired lateinit var pins: WorldArtifactIdentityReadRepository

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing seed resource: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun own(id: Int) {
        assertEquals(1, jdbc.update("UPDATE general SET user_id='7' WHERE world_id=1 AND id=?", id))
        entityManager.clear()
    }

    private fun dbRetainers(master: Int): Set<Pair<Int, Int>> = jdbc.query(
        "SELECT id,general_id FROM general_retainers WHERE world_id=1 AND master_general_id=?",
        { rs, _ -> rs.getInt("id") to rs.getInt("general_id") }, master,
    ).toSet()

    @Test
    fun `packaged initial retinue and unassessed yuedan survive cold DB reads`() {
        val root = Path.of("../..").toAbsolutePath().normalize()
        val scenario = ScenarioJson.loadScenario(resource("scenario/scenario_3190.json"))
        assertEquals("han-world-v3", scenario.map["mapName"])
        val mapCities = ScenarioJson.loadMapCities(resource("map/${MapJson.resourceCode("han-world-v3")}.json"))
        ScenarioImporter(scenario, mapCities, scenarioCode = "scenario_3190", scenarioNumber = 3190,
            artifactsRoot = root).importAll(jdbc, WorldId(1))
        entityManager.clear()

        val mapper = ObjectMapper()
        val ledgers = CampLedgers(mapper)
        val reader = CampReader(generals, worlds, nations, cities, retainers, gameKv,
            ActiveWorldArtifactResolver(worlds, cities, pins, WorldArtifactsResolver(root)),
            ledgers, CityGeography(mapper, ledgers), mapper)
        val imported = jdbc.query("SELECT id,meta::text FROM general WHERE world_id=1") { rs, _ ->
            rs.getInt("id") to MetaJson.decode(rs.getString("meta"))
        }.toMap()
        val byOfficer = imported.entries.associate { (id, meta) ->
            assertNotNull(PersonPolicyState.read(meta), "policy for imported general $id").officerId to id
        }
        val lordOfficer = assertNotNull(scenario.generals.single { it.name == "공손찬" }.personPolicy).officerId
        assertEquals(10061, lordOfficer)
        val lordId = byOfficer.getValue(lordOfficer)
        assertTrue(LordStatus.read(imported.getValue(lordId)))
        val sourceRoster = scenario.initGenerals().associateBy { it.name }
        val expectedIds = scenario.retainers.filter { it.master == "공손찬" }.mapNotNull { retainer ->
            val officer = assertNotNull(sourceRoster[retainer.general]?.personPolicy, retainer.general).officerId
            byOfficer[officer]
        }.toSet()
        val dbCards = dbRetainers(lordId)
        assertEquals(expectedIds, dbCards.map { it.second }.toSet(), "packaged direct roster vs DB")
        own(lordId)
        val initial = reader.retinue(lordId, 7)
        assertEquals("READY", initial.status)
        assertEquals(dbCards, initial.people.map { it.retainerId to assertNotNull(it.generalId) }.toSet())
        assertEquals(expectedIds, initial.people.map { it.generalId }.toSet())
        assertEquals(dbCards.size, initial.people.size, "no duplicate cards")
        assertEquals(101, initial.renown)
        assertEquals(71, initial.costSum)
        assertFalse(initial.overCapacity)
        val lordNation = generals.findById(lordId).orElseThrow().nationId
        initial.people.forEach { card ->
            val id = assertNotNull(card.generalId)
            val person = generals.findById(id).orElseThrow()
            assertEquals(lordNation, person.nationId, "nation for $id")
            val dbCity = jdbc.queryForObject("SELECT city_id FROM general WHERE world_id=1 AND id=?", Int::class.java, id)
            assertEquals(dbCity, card.locationCityId, "location for $id")
            assertTrue(cities.findById(assertNotNull(card.locationCityId)).isPresent, "existing city for $id")
            val stats = assertNotNull(card.stats)
            assertEquals(RenownRules.personCost(person.leadership, person.strength, person.intel,
                person.politics, person.charm), assertNotNull(card.cost), "cost for $id")
            assertEquals(listOf(person.leadership, person.strength, person.intel, person.politics, person.charm),
                listOf(stats.leadership, stats.strength, stats.intel, stats.politics, stats.charm))
            assertNull(card.departureOrder)
        }
        assertEquals(initial.costSum, initial.people.sumOf { assertNotNull(it.cost) })
        entityManager.clear()
        val yuedan = reader.yuedan(lordId, 7)
        assertEquals("NOT_ASSESSED", yuedan.status)
        val self = assertNotNull(yuedan.self)
        assertEquals(101, self.renown)
        assertEquals(71, self.retinueCost)
        assertFalse(self.overCapacity)

        val rulers = imported.filterValues { LordStatus.read(it) }.keys
        assertEquals(21, rulers.size)
        rulers.forEach { id ->
            own(id)
            val response = reader.retinue(id, 7)
            assertEquals("READY", response.status, "ruler $id")
            assertEquals(assertNotNull(response.costSum, "known cost for ruler $id") + 30, response.renown,
                "starting direct budget for ruler $id")
        }

        assertEquals(1, jdbc.update("""
            UPDATE general SET meta=jsonb_set(meta, '{personPolicy,renownCapacity}', '70'::jsonb, false)
            WHERE world_id=1 AND id=?
        """.trimIndent(), lordId))
        entityManager.clear()
        val deficient = reader.retinue(lordId, 7)
        assertEquals("READY", deficient.status)
        assertEquals(70, deficient.renown)
        assertEquals(71, deficient.costSum)
        assertTrue(deficient.overCapacity)
        val expectedDepartures = RenownAssessment.departures(70, initial.people.map {
            RenownAssessment.RetainerCard(it.retainerId, assertNotNull(it.cost), it.loyalty)
        })
        assertTrue(expectedDepartures.isNotEmpty())
        val ordered = deficient.people.filter { it.departureOrder != null }.sortedBy { it.departureOrder }
        assertEquals(expectedDepartures, ordered.map { it.retainerId })
        assertEquals((1..expectedDepartures.size).toList(), ordered.map { it.departureOrder })
        assertEquals(dbCards, dbRetainers(lordId), "judgement must not release DB retainers")
        assertEquals(dbCards, deficient.people.map { it.retainerId to assertNotNull(it.generalId) }.toSet())
    }

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("opensamguk.world-id") { 1 }
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
}
