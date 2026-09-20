package opensamguk.engine.boot

import java.nio.file.Path
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.HanWorldVariant
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Real HWIHA load/flush boundary; the small character fixture is not a playable Zhou scenario. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaEnlistmentPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private val artifacts = HanWorldArtifactsResolver(Path.of("../.."))
    private val bundle by lazy { artifacts.artifacts(HanWorldVariant.V3_1133) }

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: HWIHA enlistment database roundtrip NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int) {
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,tick_seconds,config,meta)
            VALUES (?, 'enlistment-storage-test',200,1,3600,
            '{"mapName":"han-world-v3","ruleProfile":"HWIHA"}'::jsonb,
            '{"lastTurnTime":"0200-01-01T00:00:00Z"}'::jsonb)""", id)
        jdbc.batchUpdate("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
            secu,secu_max,def,def_max,wall,wall_max,region) VALUES (?,?,?,1,0,100,1000,10,1000,10,1000,10,1000,10,1000,10,1000,1)""",
            bundle.cityConst.all().keys.map { arrayOf<Any>(id, it, "fixture-$it") })
        val binding = bundle.projection.bindingsByCityId.entries.sortedBy { it.key }.first { it.value.landProvinceId != null }
        jdbc.update("INSERT INTO nation(world_id,id,name,color,gold,capital_city_id,meta) VALUES (?,1,'주공국','#000000',500,?,'{\"gennum\":1,\"keep\":42}'::jsonb)", id, binding.key)
        for ((generalId, nation, npc) in listOf(Triple(1, 0, 0), Triple(2, 0, 2), Triple(10, 1, 2))) {
            jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,officer_level,gold,rice,crew,turn_time,last_turn,meta)
                VALUES (?,?,?,?,?,?,?,1000,2000,300,'0200-01-01T00:00:00Z','{"command":"휴식"}'::jsonb,?::jsonb)""",
                id, generalId, "G$generalId", nation, binding.key, npc, if (generalId == 10) 12 else 0,
                "{\"hwihaLord\":${generalId != 2},\"keep\":\"unchanged\"}")
            val topology = bundle.projection.topology
            jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
                VALUES (?,?,?,?,'LAND_PROVINCE',?,1)""", id, generalId, topology.topologyRevision, topology.contentHash, binding.value.landProvinceId)
        }
        jdbc.update("""INSERT INTO general_retainers(world_id,id,master_general_id,origin,general_id,name,relation,has_own_bugok,release_policy)
            VALUES (?,4,1,'EXISTING',2,'G2','lieutenant',true,'MUTUAL')""", id)
        jdbc.update("""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,training,morale,provisions,commander_retainer_id)
            VALUES (?,7,1,'personal',100,1,50,50,200,4)""", id)
    }
    private fun load(id: Int) = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(id)), WorldId(id),
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant },
        cityLandProvinceLoader = { variant -> artifacts.artifacts(variant).projection.bindingsByCityId
            .mapNotNull { (city, binding) -> binding.landProvinceId?.let { city to it } }.toMap() }).buildSnapshot()
    private fun enlist(world: InMemoryTurnWorld, recorder: ChangeRecorder) = HwihaEnlistmentExecutor(world, recorder) {
        EnlistmentPolicy(setOf(10), mapOf(10 to (30 - world.retainersOf(10).size * 7)), 7)
    }.execute(EnlistmentRequest(1, EnlistmentMode.NATION, 1)) { error("no random draw") }

    private fun assertSameSnapshot(expected: WorldSnapshot, actual: WorldSnapshot) {
        // Spatial snapshots are immutable classes without value equality; compare their contents.
        assertEquals(expected.waterControlSnapshot!!.statesByZoneId, actual.waterControlSnapshot!!.statesByZoneId)
        assertEquals(expected.provinceControlSnapshot!!.statesByProvinceId, actual.provinceControlSnapshot!!.statesByProvinceId)
        assertEquals(expected.generalPositionSnapshot!!.statesByGeneralId, actual.generalPositionSnapshot!!.statesByGeneralId)
        assertEquals(expected.waterControlSnapshot!!.topologyHash, actual.waterControlSnapshot!!.topologyHash)
        assertEquals(expected.provinceControlSnapshot!!.topologyHash, actual.provinceControlSnapshot!!.topologyHash)
        assertEquals(expected.generalPositionSnapshot!!.topologyHash, actual.generalPositionSnapshot!!.topologyHash)
        assertEquals(expected.waterControlSnapshot!!.topologyRevision, actual.waterControlSnapshot!!.topologyRevision)
        assertEquals(expected.provinceControlSnapshot!!.topologyRevision, actual.provinceControlSnapshot!!.topologyRevision)
        assertEquals(expected.generalPositionSnapshot!!.topologyRevision, actual.generalPositionSnapshot!!.topologyRevision)
        assertEquals(expected, actual.copy(waterControlSnapshot = expected.waterControlSnapshot,
            provinceControlSnapshot = expected.provinceControlSnapshot, generalPositionSnapshot = expected.generalPositionSnapshot))
    }

    @Test fun `real HWIHA snapshot enlistment flush and cold reload preserve personal assets and prevent duplicate relation`() {
        seed(1); seed(2)
        val before = load(1)
        val otherWorld = load(2)
        assertEquals(RuleProfile.HWIHA, before.state.ruleProfile)
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        val result = assertIs<EnlistmentExecution.Applied>(enlist(world, recorder))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val after = load(1)
        assertEquals(world.listGenerals(), after.generals)
        assertEquals(world.listNations(), after.nations)
        assertEquals(world.listRetainers(), after.retainers)
        assertEquals(before.bugoks, after.bugoks)
        assertEquals(before.cities, after.cities)
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId, after.generalPositionSnapshot!!.statesByGeneralId)
        assertSameSnapshot(otherWorld, load(2))
        assertEquals(3, after.nations.single().meta["gennum"])
        assertEquals(false, HwihaLordStatus.read(after.generals.single { it.id == 1 }.meta))
        val rebooted = InMemoryTurnWorld(after)
        assertEquals(EnlistmentFailure.ALREADY_SERVING,
            assertIs<EnlistmentExecution.Rejected>(enlist(rebooted, ChangeRecorder())).reason)
        assertEquals(2, rebooted.listRetainers().size)
        assertEquals(result.retainerId + 1, rebooted.allocateRetainerId())
    }

    @Test fun `card insert failure rolls back earlier nation and general updates and the payload can be retried`() {
        seed(3)
        val before = load(3)
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        assertIs<EnlistmentExecution.Applied>(enlist(world, recorder))
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        jdbc.execute("ALTER TABLE general_retainers ADD CONSTRAINT enlistment_failure_probe CHECK (NOT (world_id=3 AND general_id=1))")
        try {
            assertFailsWith<DataIntegrityViolationException> { flush.flush(payload) }
            assertSameSnapshot(before, load(3))
        } finally {
            jdbc.execute("ALTER TABLE general_retainers DROP CONSTRAINT enlistment_failure_probe")
        }
        flush.flush(payload)
        assertEquals(world.listGenerals(), load(3).generals)
        assertEquals(world.listRetainers(), load(3).retainers)
        assertEquals(world.listNations(), load(3).nations)
    }
}
