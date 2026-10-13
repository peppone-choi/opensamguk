package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.MonthlyAssessment
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.Scenario
import opensamguk.infra.seed.ScenarioImporter
import opensamguk.infra.seed.ScenarioJson
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RecordKind
import opensamguk.logic.renown.RenownAssessment
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.renown.RenownRules
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import kotlin.test.*

/** Packaged 3190 production seed, isolated PostgreSQL and fresh loader instances; not live turn QA. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Scenario3190FirstAssessmentRoundTripIT {
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private val worldId = WorldId(1)
    private val root = Path.of("../..").toAbsolutePath().normalize()
    private val artifacts = WorldArtifactsResolver(root)
    private val departureKinds = setOf(RecordKind.DEPARTURE_JUDGED, RecordKind.RETINUE_DEPARTED)

    @BeforeAll fun requireDocker() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — 3190 first assessment round-trip IT skipped (not passed)")
    }

    private fun inFreshDatabase(test: () -> Unit) {
        PostgreSQLContainer("postgres:16-alpine").use { postgres ->
            postgres.start()
            val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
            jdbc = JdbcTemplate(source)
            flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
                TransactionTemplate(DataSourceTransactionManager(source)))
            test()
        }
    }

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing seed resource: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun scenario() = ScenarioJson.loadScenario(resource("scenario/scenario_3190.json"))

    private fun importer(scenario: Scenario): ScenarioImporter {
        assertEquals("han-world-v3", scenario.map["mapName"])
        val cities = ScenarioJson.loadMapCities(resource("map/${MapJson.resourceCode("han-world-v3")}.json"))
        return ScenarioImporter(scenario, cities, scenarioCode = "scenario_3190", scenarioNumber = 3190,
            artifactsRoot = root)
    }

    private fun freshLoader() = WorldSnapshotLoader(jdbc,
        SeedBootstrap(scenarioCode = "scenario_3190", seedEnabled = false, artifactsRoot = root, worldId = worldId),
        worldId,
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        mapVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
        administrativeCountyIdsLoader = { artifacts.artifacts(it).projection.administrativeCountyIds },
        cityLandProvinceLoader = { variant -> artifacts.artifacts(variant).projection.bindingsByCityId
            .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap() })

    private fun capacities(snapshot: WorldSnapshot): Map<Int, Int> = snapshot.generals
        .filter { LordStatus.read(it.meta) }.associate { it.id to assertNotNull(PersonPolicyState.read(it.meta)).renownCapacity }

    private fun rulerId(snapshot: WorldSnapshot, sourceName: String): Int {
        // Production NPC names include a display prefix; identify the source-bound officer instead.
        val officerId = assertNotNull(scenario().generals.single { it.name == sourceName }.personPolicy).officerId
        val ruler = snapshot.generals.single { PersonPolicyState.read(it.meta)?.officerId == officerId }
        assertTrue(LordStatus.read(ruler.meta))
        return ruler.id
    }

    private fun placements(snapshot: WorldSnapshot) = snapshot.generals.associate { it.id to (it.nationId to it.cityId) }

    private fun countyOwnership(snapshot: WorldSnapshot): Map<Int, Set<Int>> = snapshot.nations.associate { nation ->
        nation.id to snapshot.cities.filter { it.id in snapshot.administrativeCountyIds && it.nationId == nation.id }
            .map { it.id }.toSet()
    }

    private fun ownership(snapshot: WorldSnapshot): Map<Int, Set<Int>> = snapshot.nations.associate { nation ->
        nation.id to snapshot.cities.filter { it.nationId == nation.id }.map { it.id }.toSet()
    }

    private fun cards(snapshot: WorldSnapshot, master: Int): List<RenownAssessment.RetainerCard> = snapshot.retainers
        .filter { it.masterGeneralId == master }.map { card ->
            val person = snapshot.generals.single { it.id == card.generalId }
            RenownAssessment.RetainerCard(card.id, RenownRules.personCost(person.stats.leadership,
                person.stats.strength, person.stats.intelligence, person.stats.politics, person.stats.charm), card.loyalty)
        }

    @Test fun `packaged starting retinue survives first monthly assessment flush and second cold reload`() = inFreshDatabase {
        val counts = importer(scenario()).importAll(jdbc, worldId)
        assertEquals(384, counts.general)
        assertEquals(21, counts.nation)
        assertEquals(228, jdbc.queryForObject("SELECT count(*) FROM general_retainers WHERE world_id=1", Int::class.java))
        val seededPlacements = jdbc.query("SELECT id,nation_id,city_id FROM general WHERE world_id=1") { rs, _ ->
            rs.getInt("id") to (rs.getInt("nation_id") to rs.getInt("city_id"))
        }.toMap()
        val seededCapacities = jdbc.query("SELECT id,meta::text FROM general WHERE world_id=1") { rs, _ ->
            rs.getInt("id") to MetaJson.decode(rs.getString("meta"))
        }.filter { LordStatus.read(it.second) }.associate { it.first to assertNotNull(PersonPolicyState.read(it.second)).renownCapacity }
        val seededRetainers = jdbc.query("SELECT id,master_general_id,general_id FROM general_retainers WHERE world_id=1") { rs, _ ->
            Triple(rs.getInt("id"), rs.getInt("master_general_id"), rs.getInt("general_id"))
        }.toSet()
        val seededCities = jdbc.query("SELECT id,nation_id FROM city WHERE world_id=1") { rs, _ ->
            rs.getInt("id") to rs.getInt("nation_id")
        }.toMap()
        val before = freshLoader().buildSnapshot()
        assertEquals(seededRetainers, before.retainers.map { Triple(it.id, it.masterGeneralId, it.generalId) }.toSet())
        assertEquals(seededCities, before.cities.associate { it.id to it.nationId })
        assertEquals(seededPlacements, placements(before))
        assertEquals(seededCapacities, capacities(before))
        assertEquals(384, before.generals.size)
        assertEquals(21, before.nations.size)
        assertEquals(228, before.retainers.size)
        assertEquals(21, capacities(before).size)
        capacities(before).forEach { (lord, capacity) ->
            assertEquals(cards(before, lord).sumOf { it.cost } + 30, capacity, "starting direct budget for $lord")
        }
        assertEquals(101, capacities(before).getValue(rulerId(before, "공손찬")))
        assertEquals(307, capacities(before).getValue(rulerId(before, "동탁")))
        val counties = countyOwnership(before)
        assertEquals(21, counties.size)
        // The existing seed contract counts owned nodes: 424 administrative counties plus two passes.
        val owned = ownership(before)
        assertEquals(426, owned.values.sumOf { it.size })
        assertEquals(424, counties.values.sumOf { it.size })
        assertEquals(setOf(1041, 1046), owned.values.flatten().toSet() - counties.values.flatten().toSet())
        listOf(1062, 1086).forEach { id -> assertEquals(0, before.cities.single { it.id == id }.nationId) }
        assertTrue(before.generals.all { RenownEvents.entries(it.meta).isEmpty() }, "seed has no renown events")
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        val outcome = assertNotNull(MonthlyAssessment(world, recorder, RenownAssessment.CANON).assess(190, 2))
        assertFalse(outcome.alreadyStamped)
        assertEquals(384, outcome.assessed)
        assertEquals(0, outcome.moved, "no events means no capacity delta")
        assertEquals(0, outcome.overCap)
        assertTrue(world.peekLogs().none { it.eventKind in departureKinds })
        assertTrue(world.peekLogs().none { it.eventKind == RecordKind.YUEDAN_ASSESSED })
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val after = freshLoader().buildSnapshot()
        assertEquals(before.retainers.toSet(), after.retainers.toSet())
        assertEquals(seededPlacements, placements(after))
        assertEquals(seededCapacities, capacities(after))
        assertEquals(counties, countyOwnership(after))
        assertEquals(owned, ownership(after))
        assertEquals(seededCities, after.cities.associate { it.id to it.nationId })
        listOf(1062, 1086).forEach { id -> assertEquals(0, after.cities.single { it.id == id }.nationId) }
        assertEquals("0190-02", after.state.meta[MonthlyAssessment.STAMP_KEY])
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM log_entry WHERE world_id=1 AND event_kind IN (?,?)",
            Int::class.java, RecordKind.DEPARTURE_JUDGED, RecordKind.RETINUE_DEPARTED))
        assertDeficientCapacityIsJudged(before)
    }

    private fun assertDeficientCapacityIsJudged(before: WorldSnapshot) {
        // Copy the initial cold snapshot, not the already-stamped assessed world or persisted seed.
        val lordId = rulerId(before, "공손찬")
        val lord = before.generals.single { it.id == lordId }
        val policy = assertNotNull(PersonPolicyState.read(lord.meta))
        val retinue = cards(before, lord.id)
        assertEquals(71, retinue.sumOf { it.cost })
        val released = RenownAssessment.departures(70, retinue)
        assertTrue(released.isNotEmpty())
        val altered = before.copy(generals = before.generals.map {
            if (it.id == lord.id) it.copy(meta = it.meta +
                (PersonPolicyState.META_KEY to policy.copy(renownCapacity = 70).toMetaValue())) else it
        })
        val world = InMemoryTurnWorld(altered)
        val outcome = assertNotNull(MonthlyAssessment(world, ChangeRecorder(), RenownAssessment.CANON).assess(190, 2))
        assertEquals(1, outcome.overCap)
        assertEquals(0, outcome.moved)
        val judged = world.peekLogs().filter { it.eventKind == RecordKind.DEPARTURE_JUDGED }
        assertEquals(lord.id, judged.single().generalId)
        assertEquals(lord.nationId, judged.single().nationId)
        val refs = judged.single().meta!![RecordKind.REFS_META_KEY] as Map<*, *>
        assertEquals(released, refs["retainerIds"])
        val departed = world.peekLogs().filter { it.eventKind == RecordKind.RETINUE_DEPARTED }
        assertEquals(released.size, departed.size)
        assertEquals(released.map { card -> before.retainers.single { it.id == card }.generalId }.toSet(),
            departed.map { it.generalId }.toSet())
        assertTrue(departed.all { it.nationId == lord.nationId })
        departed.forEach { record ->
            val departureRefs = record.meta!![RecordKind.REFS_META_KEY] as Map<*, *>
            assertEquals(lord.id, departureRefs["masterId"])
            assertTrue(assertIs<Int>(departureRefs["retainerId"]) in released)
        }
        assertTrue(world.peekLogs().filter { it.eventKind in departureKinds }.none { it.nationId != lord.nationId })
        assertEquals(before.retainers.toSet(), world.listRetainers().toSet(), "judgement is not actual release")
        assertEquals(capacities(before) + (lord.id to 70), world.listGenerals().filter { LordStatus.read(it.meta) }
            .associate { it.id to assertNotNull(PersonPolicyState.read(it.meta)).renownCapacity })
        assertEquals(228, jdbc.queryForObject("SELECT count(*) FROM general_retainers WHERE world_id=1", Int::class.java))
    }

    @Test fun `overdeclared Gongsun Zan starting capacity is rejected before any seed row is written`() = inFreshDatabase {
        val source = scenario()
        fun alter(rows: List<opensamguk.infra.seed.ScenarioGeneral>) = rows.map { general ->
            if (general.name == "공손찬") general.copy(personPolicy =
                assertNotNull(general.personPolicy).copy(renownCapacity = 307)) else general
        }
        val invalid = source.copy(generals = alter(source.generals), baseGenerals = alter(source.baseGenerals),
            generalEx = alter(source.generalEx), generalNeutral = alter(source.generalNeutral))
        val error = assertFailsWith<IllegalArgumentException> { importer(invalid).importAll(jdbc, worldId) }
        assertEquals("Starting retinue for 공손찬 requires capacity 101, declared 307", error.message)
        listOf("general", "nation", "world_state").forEach { table ->
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java), "$table remains empty")
        }
    }
}
