package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.gameapi.read.LiveCityOwnership
import opensamguk.gameapi.read.MapAdministrativeOwnership
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.MapJson
import opensamguk.logic.world.HanWorldVariant
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every added county in the current campaign survives a real flush and cold reload. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExpandedCityPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val root = Path.of("../..")
    private val artifacts = HanWorldArtifactsResolver(root)
    private val bootstrap = SeedBootstrap(scenarioCode = "scenario_990002", artifactsRoot = root, worldId = WorldId(1))

    private companion object {
        const val SEEDED = "expanded_city_seeded"
        const val WORK = "expanded_city_work"
    }

    private fun source(database: String) = DriverManagerDataSource().apply {
        setDriverClassName("org.postgresql.Driver")
        url = postgres.jdbcUrl.substringBefore('?').substringBeforeLast('/') + "/$database"
        username = postgres.username
        password = postgres.password
    }

    @BeforeAll fun setup() {
        assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: expanded city persistence not verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        admin = JdbcTemplate(source(postgres.databaseName))
        admin.execute("CREATE DATABASE $SEEDED")
        Flyway.configure().dataSource(source(SEEDED)).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        assertTrue(bootstrap.ensureSeeded(JdbcTemplate(source(SEEDED))))
        val dataSource = source(WORK)
        jdbc = JdbcTemplate(dataSource)
        executor = JdbcFlushExecutor(
            NamedParameterJdbcTemplate(dataSource),
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
        )
    }

    @AfterAll fun cleanup() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun restoreSeededWorld() {
        admin.execute("DROP DATABASE IF EXISTS $WORK WITH (FORCE)")
        admin.execute("CREATE DATABASE $WORK TEMPLATE $SEEDED")
    }

    private fun load() = WorldSnapshotLoader(jdbc, bootstrap, WorldId(1),
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant }).buildSnapshot()

    @Test fun `each added county persists ownership and remains in the map projection`() {
        val currentIds = MapJson.loadFromClasspath("han-world-v3").cities.map { it.id }.toSet()
        val olderIds = artifacts.artifacts(HanWorldVariant.V3_835).cityConst.all().keys
        val additions = (currentIds - olderIds).sorted()
        assertTrue(additions.isNotEmpty(), "city expansion must remain covered")
        val shardCount = System.getProperty("cityShardCount", "1").toInt()
        val shardIndex = System.getProperty("cityShardIndex", "0").toInt()
        require(shardCount in 1..additions.size)
        require(shardIndex in 0 until shardCount)
        val shards = List(shardCount) { mutableListOf<Int>() }
        additions.forEachIndexed { index, cityId -> shards[index % shardCount].add(cityId) }
        assertEquals(additions, shards.flatten().sorted())
        assertEquals(additions.size, shards.flatten().toSet().size)
        val selected = shards[shardIndex]
        val manifestDirectory = Path.of(System.getProperty("cityManifestDirectory", "build/city-shards"))
        Files.createDirectories(manifestDirectory)
        ObjectMapper().writeValue(manifestDirectory.resolve("shard-$shardIndex.json").toFile(), mapOf(
            "shardIndex" to shardIndex,
            "shardCount" to shardCount,
            "allCityIds" to additions,
            "selectedCityIds" to selected,
        ))
        println("EXPANDED_CITY_SHARD index=$shardIndex count=$shardCount cities=${selected.size} total=${additions.size}")

        restoreSeededWorld()
        val baseline = load()
        assertEquals(currentIds, baseline.cities.map { it.id }.toSet())
        val bundle = artifacts.artifacts(assertNotNull(baseline.state.hanWorldVariant))
        val mapper = ObjectMapper()
        val ownership = MapAdministrativeOwnership(mapper, "unused", "unused", "unused")
        val coordinates = MapJson.loadMap(bundle.artifactBytes("infra/src/main/resources/map/han-world-v3.json")
            .toString(Charsets.UTF_8)).cities.associateBy { it.id }
        for (destination in selected) {
            restoreSeededWorld()
            val before = assertNotNull(baseline.cities.singleOrNull { it.id == destination })
            val nextOwner = if (before.nationId == 1) 2 else 1
            val world = InMemoryTurnWorld(baseline)
            val updated = before.copy(nationId = nextOwner)
            world.updateCity(updated)
            val recorder = ChangeRecorder()
            recorder.diffCity(PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(updated))
            executor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
            val restored = load()
            assertEquals(nextOwner, restored.cities.single { it.id == destination }.nationId,
                "cold reload county=$destination")
            assertEquals(currentIds, restored.cities.map { it.id }.toSet())
            fun projectedOwners(cities: List<opensamguk.engine.turn.City>): List<Int> {
                val live = cities.mapNotNull { city ->
                    coordinates.getValue(city.id).provinceId?.let {
                        LiveCityOwnership(city.id, it, city.nationId)
                    }
                }
                return ownership.project("990002", live, bundle).provinceOccupancy.map { it.nationId }
            }
            assertEquals(projectedOwners(world.listCities()), projectedOwners(restored.cities),
                "map projection survives cold reload county=$destination")
            assertEquals(baseline.state.hanWorldVariant, restored.state.hanWorldVariant)
            println("EXPANDED_CITY_PERSISTENCE county=$destination persisted=true")
        }
    }
}
