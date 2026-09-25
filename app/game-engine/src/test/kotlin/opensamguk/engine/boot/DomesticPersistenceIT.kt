package opensamguk.engine.boot

import opensamguk.logic.domestic.CountyPolicyState
import opensamguk.logic.domestic.CorpsPolicyAssignments
import opensamguk.logic.domestic.CountyWorks

import opensamguk.logic.domestic.DomesticWork

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/**
 * 내정 입력의 저장 경계: 즉시 접수(대기) → recorder → JdbcFlushExecutor → 콜드 재로드, 순 경계 공사 진척과 도장,
 * 요격 방침의 game_env 반응 목록. JSON 재로드가 숫자 폭을 바꿔도 codec 이 같은 상태를 읽는지 본다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DomesticPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))

    @Test fun `standing orders works and reaction policies survive flush and cold reload`() {
        val id = 731
        fixture.seed(id)
        var world = cold(id)
        val county = world.administrativeCountyIds.sorted().first { world.landNodeOfCity(it) != null }
        val node = (world.landNodeOfCity(county) as opensamguk.logic.world.StrategicNodeRef.LandProvince).id
        val topology = checkNotNull(world.generalPositionSnapshot())
        // Ruler 10 owns the county and an NPC staff card 11 standing in it; the county has explicit stock.
        jdbc.update("UPDATE general SET user_id='42' WHERE world_id=? AND id=10", id)
        jdbc.update("UPDATE city SET nation_id=1, meta=?::jsonb WHERE world_id=? AND id=?", MetaJson.encode(mapOf(
            CountyWarehouse.META_KEY to CountyWarehouse(county, 0, Resources(1_000_000, 1_000_000, 0, 0, 0)).toMetaValue())),
            id, county)
        jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,officer_level,gold,rice,crew,leadership,strength,intel,politics,charm,turn_time,last_turn,meta)
            VALUES (?,11,'G11',1,?,2,0,0,0,0,60,60,90,80,60,'0200-01-01T00:00:00Z','{"command":"휴식"}'::jsonb,'{}'::jsonb)""", id, county)
        jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
            VALUES (?,11,?,?,'LAND_PROVINCE',?,1)""", id, topology.topologyRevision, topology.topologyHash, node)
        jdbc.update("""INSERT INTO general_retainers(world_id,id,master_general_id,origin,general_id,name,relation,has_own_bugok,release_policy)
            VALUES (?,5,10,'EXISTING',11,'G11','staff',false,'MUTUAL')""", id)
        val corps = DeploymentState(listOf(DeployedCorps("o1", 10, 11, 5, 1, listOf(7), Phase(200, 1, 1))))
        jdbc.update("UPDATE general SET meta = meta || ?::jsonb WHERE world_id=? AND id=10",
            MetaJson.encode(mapOf(DeploymentState.META_KEY to corps.toMetaValue())), id)
        jdbc.update("UPDATE world_state SET meta = meta || ?::jsonb WHERE id=?",
            MetaJson.encode(mapOf(MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue())), id)
        world = cold(id)

        val context = DomesticContext()
        var recorder = ChangeRecorder()
        fun submit(inputId: String, body: String) =
            CourtHandler(world, recorder, context).handle(ImmediateInput("req-$inputId", 10, 42, inputId, body))
        assertTrue(submit("policy.set", """{"scope":"COUNTY","countyId":$county,"policy":"COMMERCE"}""").ok)
        assertTrue(submit("work.start", """{"countyId":$county,"work":"IRRIGATION"}""").ok)
        assertTrue(submit("policy.set", """{"scope":"CORPS","orderId":"o1","policy":"INTERCEPT"}""").ok)
        save(world, recorder)

        world = cold(id)
        val city = world.getCityById(county)!!
        assertEquals("COMMERCE", CountyPolicyState.read(city.meta)!!.slot.pending!!.policy)
        assertEquals(DomesticWork.IRRIGATION, CountyWorks.read(city.meta)!!.active!!.work)
        assertEquals("INTERCEPT", CorpsPolicyAssignments.read(world.getGeneralById(10)!!.meta)!!.forOrder("o1")!!.slot.pending!!.policy)

        // Commander's turn: the corps policy activates and the reaction inventory persists through game_env.
        recorder = ChangeRecorder()
        DomesticTurn(world, recorder, context).beforeMovement(11)
        save(world, recorder)
        world = cold(id)
        assertEquals(listOf("o1"), assertIs<MarchReactions.Inventory>(MarchReactions.read(world.getState().meta))
            .interceptions.map { it.orderId })

        // Next phase boundary: the work advances once, pays its installment, and the stamp blocks a replay after reload.
        recorder = ChangeRecorder()
        world.setCurrentDate(200, 1, 2)
        assertFalse(DomesticBoundary(world, recorder, context).run()!!.alreadyStamped)
        save(world, recorder)
        world = cold(id)
        world.setCurrentDate(200, 1, 2)
        val progressed = CountyWorks.read(world.getCityById(county)!!.meta)!!.active!!
        assertTrue(progressed.progress > 0)
        assertEquals(progressed.charged, Resources(1_000_000, 1_000_000, 0, 0, 0)
            .debit(CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock))
        assertTrue(DomesticBoundary(world, ChangeRecorder(), context).run()!!.alreadyStamped)
    }
}
