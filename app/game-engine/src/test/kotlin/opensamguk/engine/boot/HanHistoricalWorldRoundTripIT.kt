package opensamguk.engine.boot

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
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
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HanHistoricalWorldRoundTripIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val artifacts = HanWorldArtifactsResolver(Path.of("../.."))

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: historical database round trip not verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        HanWorldVariant.entries.forEachIndexed { index, variant ->
            val id = index + 1
            jdbc.update("INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,config,meta) VALUES (?, 'scenario_1020',200,1,60,?::jsonb,'{}'::jsonb)",
                id, "{\"mapName\":\"han-world-v3\"}")
            val bundle = artifacts.artifacts(variant)
            jdbc.batchUpdate("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
                secu,secu_max,def,def_max,wall,wall_max,region) VALUES (?,?,?,1,0,100,1000,10,1000,10,1000,10,1000,10,1000,10,1000,1)""",
                bundle.cityConst.all().keys.map { arrayOf<Any>(id, it, "world-$id-renamed-$it") })
            val topology = bundle.projection.topology
            jdbc.update("INSERT INTO province_control(world_id,province_id,topology_revision,topology_hash,nation_id,revision) VALUES (?,?,?,?,0,1)",
                id, topology.landProvinceIds.sorted().first(), topology.topologyRevision, topology.contentHash)
        }
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    private fun loader(id: Int) = WorldSnapshotLoader(jdbc,
        SeedBootstrap(scenarioCode = "scenario_0", seedEnabled = false, worldId = WorldId(id)), WorldId(id),
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant })

    @Test fun `real flush and fresh loader preserve both world identities and changed names`() {
        HanWorldVariant.entries.forEachIndexed { index, variant ->
            val id = index + 1
            val before = loader(id).buildSnapshot()
            assertEquals(variant, before.state.hanWorldVariant)
            assertEquals(artifacts.artifacts(variant).cityConst.all().keys, before.cities.map { it.id }.toSet())
            val cityId = before.cities.first().id
            val newName = "world-$id-renamed-$cityId"
            assertEquals(newName, before.cities.first().name)
            val savedClock = Instant.parse("2026-09-13T00:00:00Z")
            executor.flush(FlushPayload(worldId = WorldId(id),
                worldStateUpdate = linkedMapOf("id" to id, "current_year" to 201, "current_month" to 2, "last_turn_time" to savedClock),
                updatedCities = listOf(opensamguk.logic.domain.City(id = cityId, nationId = 0, level = 1, commerce = 1234, commerceMax = 2000,
                    agriculture = 10, agricultureMax = 1000, supplyState = 0, frontState = 0, trust = 0.0))))
            val after = loader(id).buildSnapshot()
            assertEquals(variant, after.state.hanWorldVariant)
            assertEquals(before.cities.map { it.id }, after.cities.map { it.id })
            assertEquals(newName, after.cities.single { it.id == cityId }.name)
            assertEquals(1234, after.cities.single { it.id == cityId }.commerce)
            assertEquals(201, after.state.currentYear)
            assertEquals(2, after.state.currentMonth)
            assertEquals(savedClock, after.state.lastTurnTime)
            assertEquals(artifacts.artifacts(variant).projection.topology.contentHash,
                assertNotNull(after.waterControlSnapshot).topologyHash)
            assertEquals("han-world-v3", after.state.config["mapName"])
            assertFalse(after.state.config.containsKey("hanWorldVariant"))
            assertFalse(after.state.meta.containsKey("hanWorldVariant"))
        }
    }

    @Test fun `another worlds inconsistent pin cannot change this worlds selected map`() {
        val older = artifacts.artifacts(HanWorldVariant.V3_832).projection.topology
        val newer = artifacts.artifacts(HanWorldVariant.V3_835).projection.topology
        jdbc.update("UPDATE province_control SET topology_hash=? WHERE world_id=2", older.contentHash)
        try {
            assertEquals(HanWorldVariant.V3_832, loader(1).buildSnapshot().state.hanWorldVariant)
            assertFailsWith<IllegalArgumentException> { loader(2).buildSnapshot() }
        } finally {
            jdbc.update("UPDATE province_control SET topology_hash=? WHERE world_id=2", newer.contentHash)
        }
    }
}
