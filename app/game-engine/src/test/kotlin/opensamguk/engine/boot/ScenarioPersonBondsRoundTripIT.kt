package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.ScenarioImporter
import opensamguk.infra.seed.ScenarioJson
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.content.PersonBond
import opensamguk.logic.content.PersonBondKind
import opensamguk.logic.content.PersonBondState
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import kotlin.test.assertEquals

/** Actual 3190 importer → isolated PostgreSQL → two fresh loader instances, not a new process. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioPersonBondsRoundTripIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private val worldId = WorldId(1)
    private val root = Path.of("../..").toAbsolutePath().normalize()
    private val artifacts = WorldArtifactsResolver(root)

    @BeforeAll fun setup() {
        assumeTrue(runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — person bond round-trip IT skipped (not passed)")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
    }

    @AfterAll fun cleanup() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    private fun resource(path: String): String = requireNotNull(javaClass.classLoader.getResourceAsStream(path)) {
        "Missing seed resource: $path"
    }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun persistedBonds(): Map<Int, Set<PersonBond>> =
        jdbc.query("SELECT id, meta::text FROM general WHERE world_id=1") { rs, _ ->
            rs.getInt("id") to PersonBondState.read(MetaJson.decode(rs.getString("meta")))?.bonds.orEmpty()
        }.toMap().filterValues { it.isNotEmpty() }

    private fun freshLoader() = WorldSnapshotLoader(jdbc,
        SeedBootstrap(scenarioCode = "scenario_3190", seedEnabled = false, artifactsRoot = root, worldId = worldId),
        worldId,
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        mapVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
        administrativeCountyIdsLoader = { artifacts.artifacts(it).projection.administrativeCountyIds },
        cityLandProvinceLoader = { variant -> artifacts.artifacts(variant).projection.bindingsByCityId
            .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap() })

    @Test fun `all six 3190 oath edges retain kind target and evidence across two cold reloads`() {
        val scenario = ScenarioJson.loadScenario(resource("scenario/scenario_3190.json"))
        val mapName = scenario.map["mapName"] as? String ?: "han-world-v2"
        val cities = ScenarioJson.loadMapCities(resource("map/${MapJson.resourceCode(mapName)}.json"))
        val counts = ScenarioImporter(scenario, cities, scenarioCode = "scenario_3190", scenarioNumber = 3190,
            artifactsRoot = root).importAll(jdbc, worldId)
        assertEquals(384, counts.general)
        val oathIds = listOf("10071.png", "10853.png", "10357.png").map { portrait ->
            jdbc.queryForObject("SELECT id FROM general WHERE world_id=1 AND picture=?", Int::class.java, portrait)!!
        }.toSet()
        assertEquals(3, oathIds.size)
        val expected = oathIds.associateWith { ownerId ->
            (oathIds - ownerId).map { targetId ->
                PersonBond(PersonBondKind.OATH, "general:$targetId", setOf("novel:三國演義:第一回"))
            }.toSet()
        }
        assertEquals(6, expected.values.sumOf { it.size })
        assertEquals(expected, persistedBonds())
        repeat(2) { reload ->
            val snapshot = freshLoader().buildSnapshot()
            assertEquals(384, snapshot.generals.size)
            val restored = snapshot.generals.associate { general ->
                general.id to PersonBondState.read(general.meta)?.bonds.orEmpty()
            }.filterValues { it.isNotEmpty() }
            assertEquals(expected, restored, "cold reload ${reload + 1}")
            assertEquals(expected, persistedBonds(), "reload must not rewrite stored bonds")
        }
    }
}
