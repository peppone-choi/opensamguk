package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.world.WorldFormat
import java.nio.file.Path
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.MethodOrderer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real current-scenario seed, reload, and fail-closed boot contract. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ScenarioBootIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var loader: WorldSnapshotLoader
    private val artifactsRoot = Path.of("../..").toAbsolutePath().normalize()
    private val artifacts = HanWorldArtifactsResolver(artifactsRoot)
    private val bootstrap = SeedBootstrap(scenarioCode = "scenario_990002", worldId = WorldId(1),
        artifactsRoot = artifactsRoot)
    private var dockerAvailable = false

    @BeforeAll fun setup() {
        dockerAvailable = runCatching {
            org.testcontainers.DockerClientFactory.instance().isDockerAvailable
        }.getOrDefault(false)
        if (!dockerAvailable) return
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
        loader = WorldSnapshotLoader(jdbc, bootstrap, WorldId(1),
            waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
            hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
            administrativeCountyIdsLoader = { artifacts.artifacts(it).projection.administrativeCountyIds },
            cityLandProvinceLoader = { variant -> artifacts.artifacts(variant).projection.bindingsByCityId
                .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap() })
    }

    @AfterAll fun cleanup() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test @Order(1)
    fun `current scenario seeds and reloads every county and lord`() {
        assumeTrue(dockerAvailable)
        assertTrue(bootstrap.ensureSeeded(jdbc))
        val snapshot = loader.buildSnapshot()
        assertEquals(WorldFormat.GENERAL_RETAINER_CAMPAIGN.name, snapshot.state.config[WorldFormat.CONFIG_KEY])
        assertEquals(MapJson.loadFromClasspath("han-world-v3").cities.map { it.id }.toSet(),
            snapshot.cities.map { it.id }.toSet())
        assertEquals(6, snapshot.nations.size)
        assertEquals(6, snapshot.generals.size)
        assertFalse(bootstrap.ensureSeeded(jdbc))
    }

    @Test @Order(2)
    fun `county and person state survive a cold reload`() {
        assumeTrue(dockerAvailable)
        val cityId = jdbc.queryForObject("SELECT min(id) FROM city WHERE world_id=1", Int::class.java)!!
        val generalId = jdbc.queryForObject("SELECT min(id) FROM general WHERE world_id=1", Int::class.java)!!
        jdbc.update("UPDATE city SET state=4 WHERE world_id=1 AND id=?", cityId)
        jdbc.update("UPDATE general SET politics=73, charm=84 WHERE world_id=1 AND id=?", generalId)
        val snapshot = loader.buildSnapshot()
        assertEquals(4, snapshot.cities.single { it.id == cityId }.state)
        val general = snapshot.generals.single { it.id == generalId }
        assertEquals(73, general.stats.politics)
        assertEquals(84, general.stats.charm)
    }

    @Test @Order(3)
    fun `missing and retired world formats are refused at boot`() {
        assumeTrue(dockerAvailable)
        val original = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id=1", String::class.java)!!
        try {
            jdbc.update("UPDATE world_state SET config = config - 'worldFormat' WHERE id=1")
            assertFailsWith<IllegalArgumentException> { loader.buildSnapshot() }
            jdbc.update("""UPDATE world_state SET config = jsonb_build_object('ruleProfile', 'SAMMO') WHERE id=1""")
            assertFailsWith<IllegalArgumentException> { loader.buildSnapshot() }
        } finally {
            jdbc.update("UPDATE world_state SET config=?::jsonb WHERE id=1", original)
        }
        assertEquals(WorldFormat.GENERAL_RETAINER_CAMPAIGN.name,
            loader.buildSnapshot().state.config[WorldFormat.CONFIG_KEY])
    }

    @Test @Order(4)
    fun `current world without a general position is refused at boot`() {
        assumeTrue(dockerAvailable)
        jdbc.update("""DELETE FROM general_spatial_position WHERE world_id=1
            AND general_id=(SELECT min(id) FROM general WHERE world_id=1)""")
        val error = assertFailsWith<IllegalArgumentException> { loader.buildSnapshot() }
        assertTrue(error.message.orEmpty().contains("generals without a position row"), error.message)
    }
}
