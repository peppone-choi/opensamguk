package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.MapJson
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.HanWorldVariant
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real city/general command persistence; authenticated intake and spatial-row writes have separate coverage. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HanExpandedCityCommandRoundTripIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val artifacts = HanWorldArtifactsResolver(Path.of("../.."))

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: expanded city command persistence not verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    @Test fun `each city added since historical roster supports move conquest and cold reload`() {
        val currentIds = MapJson.loadFromClasspath("han-world-v3").cities.map { it.id }.toSet()
        val olderIds = artifacts.artifacts(HanWorldVariant.V3_835).cityConst.all().keys
        val additions = (currentIds - olderIds).sorted()
        assertTrue(additions.isNotEmpty(), "expansion evidence must exercise added cities")
        for (destination in additions) for (command in listOf("che_이동", "che_출병")) {
            // SeedBootstrap deliberately requires a single configured world per database.
            // This PostgreSQL container belongs only to this test class.
            jdbc.execute("TRUNCATE TABLE world_state CASCADE")
            val worldId = WorldId(1)
            val bootstrap = SeedBootstrap(scenarioCode = "scenario_1020", worldId = worldId)
            assertTrue(bootstrap.ensureSeeded(jdbc))
            fun load() = WorldSnapshotLoader(jdbc, bootstrap, worldId,
                waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
                hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant }).buildSnapshot()
            val baseline = load()
            val bundle = artifacts.artifacts(assertNotNull(baseline.state.hanWorldVariant))
            val sourceId = assertNotNull(bundle.cityConst.byId(destination)).path.keys.sorted().first()
            assertTrue(destination in assertNotNull(bundle.cityConst.byId(sourceId)).path)
            val actor = baseline.generals.first { it.nationId > 0 }
            val nationId = actor.nationId
            val enemyId = baseline.nations.first { it.id != nationId }.id
            val reserveId = currentIds.sorted().first { it != sourceId && it != destination }
            val attack = command == "che_출병"
            val fixture = InMemoryTurnWorld(baseline)
            // Controlled resources/opposition; real route constraints and combat remain enabled.
            baseline.cities.forEach { city ->
                fixture.updateCity(city.copy(
                    nationId = when { city.id == reserveId -> enemyId; attack && city.id == destination -> enemyId; else -> nationId },
                    supplyState = 1, frontState = 3,
                    population = 100_000, populationMax = 100_000,
                    agriculture = 20_000, agricultureMax = 20_000,
                    commerce = 20_000, commerceMax = 20_000,
                    security = 20_000, securityMax = 20_000,
                    defence = if (city.id == destination) 1 else 20_000,
                    wall = if (city.id == destination) 1 else 20_000,
                ))
            }
            val unitSet = baseline.state.meta["unitSet"] as? String ?: UnitSetTable.CHE_UNIT_SET
            val crewType = assertNotNull(UnitSetTable.defaultCrewTypeId(unitSet))
            baseline.generals.forEach { general ->
                fixture.updateGeneral(if (general.id == actor.id) general.copy(
                    cityId = sourceId, officerLevel = 1, troopId = 0,
                    crew = 50_000, crewTypeId = crewType, train = 100, atmos = 100,
                    gold = 1_000_000, rice = 1_000_000,
                    stats = GeneralStats(leadership = 100, strength = 100, intelligence = 90),
                ) else general.copy(cityId = reserveId, nationId = 0, officerLevel = 1, troopId = 0))
            }
            baseline.nations.forEach { nation ->
                fixture.updateNation(nation.copy(capitalCityId = if (nation.id == nationId) sourceId else reserveId,
                    gold = 1_000_000, rice = 1_000_000))
            }
            val prepRecorder = ChangeRecorder()
            baseline.generals.forEach { before -> prepRecorder.diffGeneral(
                PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(fixture.getGeneralById(before.id)!!)) }
            baseline.cities.forEach { before -> prepRecorder.diffCity(
                PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(fixture.getCityById(before.id)!!)) }
            baseline.nations.forEach { before -> prepRecorder.diffNation(
                PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(fixture.getNationById(before.id)!!)) }
            for ((from, to) in listOf(nationId to enemyId, enemyId to nationId)) {
                val previous = fixture.getDiplomacy(from, to)
                if (previous == null) fixture.createDiplomacy(TurnDiplomacy(from, to, state = 0, term = 0))
                else {
                    val next = assertNotNull(fixture.updateDiplomacy(from, to, 0, 0))
                    prepRecorder.diffDiplomacy(previous, next)
                }
            }
            executor.flush(DatabaseHooks.toFlushPayload(fixture, prepRecorder, fixture.consumeDirtyState()))
            val world = InMemoryTurnWorld(load())
            assertEquals(sourceId, world.getGeneralById(actor.id)!!.cityId)
            assertEquals(if (attack) enemyId else nationId, world.getCityById(destination)!!.nationId)
            val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()),
                world.getState().meta["hiddenSeed"] as String,
                (world.getState().meta["startYear"] as Number).toInt())
            val handled = handler.handle(actor.id, ReservedTurn(command, "{\"destCityID\":$destination}"),
                year = world.getState().currentYear + 5, month = 1, date = "12:00")
            assertFalse(handled.fellBack, "$command destination=$destination")
            assertEquals(command, handled.definition.key)
            assertEquals(destination, world.getGeneralById(actor.id)!!.cityId, "$command movement destination=$destination")
            assertEquals(nationId, world.getCityById(destination)!!.nationId, "$command owner destination=$destination")
            val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())
            assertTrue(payload.updatedGenerals.any { it.id == actor.id })
            if (attack) assertTrue(payload.updatedCities.any { it.id == destination })
            executor.flush(payload)
            val restored = load()
            assertEquals(destination, restored.generals.single { it.id == actor.id }.cityId)
            assertEquals(nationId, restored.cities.single { it.id == destination }.nationId)
            assertEquals(currentIds, restored.cities.map { it.id }.toSet())
            assertEquals(baseline.state.hanWorldVariant, restored.state.hanWorldVariant)
            assertEquals(bundle.projection.topology.contentHash, assertNotNull(restored.waterControlSnapshot).topologyHash)
            println("EXPANDED_CITY_COMMAND destination=$destination command=$command persisted=true")
        }
    }
}
