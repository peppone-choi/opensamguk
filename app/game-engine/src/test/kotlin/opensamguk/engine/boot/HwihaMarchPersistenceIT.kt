package opensamguk.engine.boot

import java.nio.file.Path
import kotlin.test.*
import opensamguk.logic.war.hwiha.HwihaEncounterCombatProfiles
import opensamguk.logic.war.hwiha.HwihaBattlePlans
import opensamguk.infra.seed.HwihaUnitProfilesJson
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.hwiha.*
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Storage boundary only: synthetic people and explicit encounter observations on the real pinned map. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaMarchPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: HwihaEnlistmentFixture
    private val bundle by lazy { HanWorldArtifactsResolver(Path.of("../..")).artifacts(HanWorldVariant.V3_1133) }
    private val topology get() = bundle.projection.topology
    private val metrics get() = bundle.landMarchMetrics
    private fun edges(rows: Map<String, StrategicEdgeState> = emptyMap()) =
        StrategicEdgeStateSnapshot(topology.topologyRevision,topology.contentHash,rows)

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres=PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source=DriverManagerDataSource(postgres.jdbcUrl,postgres.username,postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc=JdbcTemplate(source)
        flush=JdbcFlushExecutor(NamedParameterJdbcTemplate(source),TransactionTemplate(DataSourceTransactionManager(source)))
        fixture=HwihaEnlistmentFixture(jdbc,flush)
    }
    @AfterAll fun teardown() { if(this::postgres.isInitialized) postgres.stop() }
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world,recorder,world.consumeDirtyState()))
    private fun cold(id: Int) = InMemoryTurnWorld(fixture.load(id))
    private fun executor(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        HwihaAssignmentMarchExecutor(world,recorder,topology,metrics,requiredCapacity=1)
    private fun stored(world: InMemoryTurnWorld) = HwihaMarchState.read(world.getGeneralById(1)!!.meta,topology,metrics)!!
    private fun nextPhase(world: InMemoryTurnWorld) {
        val now=world.getState().let { HwihaPhase(it.currentYear,it.currentMonth,it.currentPhase) }.plus(1)
        world.setCurrentDate(now.year,now.month,now.phase)
    }
    private fun seed(id: Int): InMemoryTurnWorld {
        fixture.seed(id)
        val initial=cold(id)
        val source=initial.positionOf(1)!!
        val county=initial.administrativeCountyIds.sorted().first { county ->
            val target=initial.landNodeOfCity(county) ?: return@first false
            val path=(StrategicPathResolver.resolveLandMarch(topology,StrategicPathRequest(source,target,1),edges(),metrics)
                as? LandMarchPathResult.Resolved)?.path ?: return@first false
            path.totalCostMm in 30_000_001L..120_000_000L
        }
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id=?",id,county)
        jdbc.update("UPDATE general SET user_id=42 WHERE world_id=? AND id=1",id)
        val world=cold(id);val recorder=ChangeRecorder()
        assertIs<EnlistmentExecution.Applied>(HwihaEnlistmentExecutor(world,recorder)
            .execute(EnlistmentRequest(1,EnlistmentMode.NATION,1)) { error("direct enlistment") })
        val court=HwihaDispatchExecutor(world,recorder)
        assertIs<DispatchExecution.Applied>(court.issue("march-$id",DispatchRequest(10,1,county)))
        assertIs<DispatchExecution.Applied>(court.reply(DispatchReplyRequest(1,"march-$id",true)))
        save(world,recorder)
        return cold(id)
    }

    @Test fun `stale position flush rolls back march metadata and phase together`() {
        val id=605;var world=seed(id)
        repeat(5) {
            val before=cold(id)
            val recorder=ChangeRecorder()
            val result=assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder)
                .advance(1,edges()) { LandMarchEntry.CLEAR })
            if(result.movement.reachedNodes.isNotEmpty()) {
                jdbc.update("UPDATE general_spatial_position SET revision=revision+1 WHERE world_id=? AND general_id=1",id)
                assertFailsWith<opensamguk.infra.persistence.StaleGeneralPositionException> { save(world,recorder) }
                val restored=cold(id)
                assertEquals(before.getGeneralById(1),restored.getGeneralById(1))
                assertEquals(before.positionOf(1),restored.positionOf(1))
                assertEquals(before.getState().currentPhase,restored.getState().currentPhase)
                return
            }
            save(world,recorder);world=cold(id);nextPhase(world)
        }
        fail("fixture must reach a province within five phases")
    }

    @Test fun `partial progress cold reload closure reopening and arrival preserve one phase limit`() {
        val id=601;var world=seed(id)
        val personal=world.getGeneralById(1)!!
        val cards=world.listRetainers();val bugoks=world.listBugoks()
        var recorder=ChangeRecorder()
        val first=assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder).advance(1,edges()) { LandMarchEntry.CLEAR })
        assertEquals(30_000_000L,first.movement.spentMm)
        assertEquals(LandMarchStop.BUDGET_EXHAUSTED,first.state.stop)
        save(world,recorder);world=cold(id)
        val reloaded=stored(world)
        assertEquals(first.state.cursor,reloaded.cursor);assertEquals(first.state.path.pathHash,reloaded.path.pathHash)
        val beforeDuplicate=world.getGeneralById(1)
        assertIs<AssignmentMarchExecution.AlreadyProcessed>(executor(world,ChangeRecorder()).advance(1,edges()) { error("same phase") })
        assertEquals(beforeDuplicate,world.getGeneralById(1))
        nextPhase(world);recorder=ChangeRecorder()
        val edgeId=reloaded.path.edgeIds[reloaded.cursor.edgeIndex]
        val closed=assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder)
            .advance(1,edges(mapOf(edgeId to StrategicEdgeState(blockaded=true)))) { error("closed edge") })
        assertEquals(LandMarchStop.EDGE_BLOCKED,closed.state.stop)
        assertEquals(reloaded.cursor,closed.state.cursor);assertEquals(0L,closed.movement.spentMm)
        save(world,recorder);world=cold(id)
        repeat(5) {
            if(stored(world).stop != LandMarchStop.ARRIVED) {
                nextPhase(world);recorder=ChangeRecorder()
                assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder).advance(1,edges()) { LandMarchEntry.CLEAR })
                save(world,recorder);world=cold(id)
            }
        }
        val arrived=stored(world)
        assertEquals(LandMarchStop.ARRIVED,arrived.stop)
        assertEquals(world.landNodeOfCity(arrived.assignment.countyId),world.positionOf(1))
        assertEquals(arrived.assignment.countyId,world.getGeneralById(1)!!.cityId)
        assertEquals(personal.turnTime,world.getGeneralById(1)!!.turnTime)
        assertEquals(personal.gold,world.getGeneralById(1)!!.gold)
        assertEquals(cards,world.listRetainers());assertEquals(bugoks,world.listBugoks())
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE world_id=?",Int::class.java,id))
    }

    @Test fun `unknown encounter persists no movement and reached encounter cannot auto resume after reload`() {
        val id=602;var world=seed(id);val origin=world.positionOf(1)
        var recorder=ChangeRecorder()
        val unknown=assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder).advance(1,edges()) { LandMarchEntry.UNAVAILABLE })
        assertEquals(LandMarchStop.ENCOUNTER_UNAVAILABLE,unknown.state.stop)
        assertEquals(0L,unknown.movement.spentMm);assertEquals(origin,world.positionOf(1))
        save(world,recorder);world=cold(id)
        repeat(5) {
            if(stored(world).stop != LandMarchStop.ENCOUNTER) {
                nextPhase(world);recorder=ChangeRecorder()
                assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder).advance(1,edges()) { LandMarchEntry.ENCOUNTER })
                save(world,recorder);world=cold(id)
            }
        }
        assertEquals(LandMarchStop.ENCOUNTER,stored(world).stop)
        val stoppedAt=world.positionOf(1);nextPhase(world)
        recorder=ChangeRecorder()
        val county=stored(world).assignment.countyId
        val replacement=HwihaDispatchExecutor(world,recorder)
        assertIs<DispatchExecution.Applied>(replacement.issue("encounter-replacement",DispatchRequest(10,1,county)))
        assertIs<DispatchExecution.Applied>(replacement.reply(DispatchReplyRequest(1,"encounter-replacement",true)))
        save(world,recorder);world=cold(id)
        assertEquals(AssignmentMarchFailure.BATTLE_PENDING,assertIs<AssignmentMarchExecution.Rejected>(
            executor(world,ChangeRecorder()).advance(1,edges()) { error("unresolved combat") }).reason)
        assertEquals(stoppedAt,world.positionOf(1))
    }

    @Test fun `captured destination and malformed stored progress never start a replacement route`() {
        val id=603;var world=seed(id);val origin=world.positionOf(1)
        val county=HwihaCountyAssignment.read(world.getGeneralById(1)!!.meta)!!.countyId
        jdbc.update("UPDATE city SET nation_id=0 WHERE world_id=? AND id=?",id,county);world=cold(id)
        assertEquals(AssignmentMarchFailure.INVALID_ASSIGNMENT,assertIs<AssignmentMarchExecution.Rejected>(
            executor(world,ChangeRecorder()).advance(1,edges()) { error("captured destination") }).reason)
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id=?",id,county)
        jdbc.update("UPDATE general SET meta=meta || '{\"hwihaMarch\":{\"bad\":true}}'::jsonb WHERE world_id=? AND id=1",id)
        world=cold(id)
        assertEquals(AssignmentMarchFailure.INVALID_STATE,assertIs<AssignmentMarchExecution.Rejected>(
            executor(world,ChangeRecorder()).advance(1,edges()) { error("corrupt state") }).reason)
        assertEquals(origin,world.positionOf(1))
    }
    @Test fun `new accepted assignment replans at reached node but never hides a mismatched stored cursor`() {
        val id=604;var world=seed(id);var recorder=ChangeRecorder()
        assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder).advance(1,edges()) { LandMarchEntry.CLEAR })
        save(world,recorder);world=cold(id)
        val old=stored(world);assertTrue(old.cursor.paidMm > 0)
        val origin=world.positionOf(1)!!
        recorder=ChangeRecorder()
        val court=HwihaDispatchExecutor(world,recorder)
        assertIs<DispatchExecution.Applied>(court.issue("replacement-604",DispatchRequest(10,1,old.assignment.countyId)))
        assertIs<DispatchExecution.Applied>(court.reply(DispatchReplyRequest(1,"replacement-604",true)))
        save(world,recorder);world=cold(id)
        assertIs<AssignmentMarchExecution.AlreadyProcessed>(executor(world,ChangeRecorder()).advance(1,edges()) { error("new order is not a second turn") })
        nextPhase(world);recorder=ChangeRecorder()
        val changed=assertIs<AssignmentMarchExecution.Applied>(executor(world,recorder).advance(1,edges()) { LandMarchEntry.CLEAR })
        assertEquals("replacement-604",changed.state.assignment.dispatchId)
        assertEquals(origin.canonicalKey,changed.state.path.nodeKeys.first())
        val completedCost=old.path.edgeIds.take(old.cursor.edgeIndex).sumOf { metrics.edgesById.getValue(it).costMm }
        assertEquals(old.path.totalCostMm-completedCost,changed.state.path.totalCostMm)
        save(world,recorder);world=cold(id)
        nextPhase(world);recorder=ChangeRecorder()
        val nextCourt=HwihaDispatchExecutor(world,recorder)
        assertIs<DispatchExecution.Applied>(nextCourt.issue("replacement-corrupt",DispatchRequest(10,1,old.assignment.countyId)))
        assertIs<DispatchExecution.Applied>(nextCourt.reply(DispatchReplyRequest(1,"replacement-corrupt",true)))
        val different=topology.landProvinceIds.sorted().first { StrategicNodeRef.LandProvince(it) != world.positionOf(1) }
        assertIs<GeneralPositionChangeResult.Changed>(recorder.moveGeneral(world,1,StrategicNodeRef.LandProvince(different)))
        save(world,recorder);world=cold(id)
        val before=world.getGeneralById(1);val beforePosition=world.positionOf(1)
        assertEquals(AssignmentMarchFailure.INVALID_STATE,assertIs<AssignmentMarchExecution.Rejected>(
            executor(world,ChangeRecorder()).advance(1,edges()) { error("mismatch cannot reset") }).reason)
        assertEquals(before,world.getGeneralById(1));assertEquals(beforePosition,world.positionOf(1))
    }

    private fun corpsExecutor(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        HwihaCorpsMarchExecutor(world, recorder, topology, metrics, requiredCapacity = 1)
    private fun corpsState(world: InMemoryTurnWorld, commander: Int) =
        HwihaCorpsMarchState.read(world.getGeneralById(commander)!!.meta, topology, metrics)!!
    private fun destination(world: InMemoryTurnWorld) =
        assertIs<StrategicNodeRef.LandProvince>(world.landNodeOfCity(HwihaCountyAssignment.read(world.getGeneralById(1)!!.meta)!!.countyId))
    private fun deploy(world: InMemoryTurnWorld, recorder: ChangeRecorder, id: Int, deputy: Boolean = false) =
        assertIs<DeploymentExecution.Applied>(HwihaDeploymentExecutor(world, recorder, topology, metrics)
            .deploy("corps-$id", DeploymentRequest(1, if (deputy) 4 else null, listOf(7)))).corps

    @Test fun `corps takes over assignment only next phase and retains partial progress across closure and reload`() {
        val id = 606; seed(id)
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND id=7", id)
        var world = cold(id); var recorder = ChangeRecorder()
        val target = destination(world); val assignment = HwihaCountyAssignment.read(world.getGeneralById(1)!!.meta)
        val units = world.listBugoks(); val personalTime = world.getGeneralById(1)!!.turnTime
        assertIs<AssignmentMarchExecution.Applied>(executor(world, recorder).advance(1, edges()) { LandMarchEntry.CLEAR })
        deploy(world, recorder, id)
        assertIs<CorpsMarchExecution.AlreadyProcessed>(corpsExecutor(world, recorder)
            .advance("corps-$id", 1, target, edges()) { error("one movement per phase") })
        save(world, recorder); world = cold(id); nextPhase(world); recorder = ChangeRecorder()
        val first = assertIs<CorpsMarchExecution.Applied>(corpsExecutor(world, recorder)
            .advance("corps-$id", 1, target, edges()) { LandMarchEntry.CLEAR })
        assertEquals(30_000_000L, first.movement.spentMm)
        assertEquals(assignment, HwihaCountyAssignment.read(world.getGeneralById(1)!!.meta))
        assertNull(HwihaMarchState.read(world.getGeneralById(1)!!.meta, topology, metrics))
        assertEquals(AssignmentMarchFailure.CORPS_DEPLOYED, assertIs<AssignmentMarchExecution.Rejected>(
            executor(world, recorder).advance(1, edges()) { error("corps owns movement") }).reason)
        save(world, recorder); world = cold(id)
        val resumed = corpsState(world, 1).checkpoint
        assertEquals(first.state.checkpoint.cursor, resumed.cursor)
        assertEquals(first.state.checkpoint.path.pathHash, resumed.path.pathHash)
        assertIs<CorpsMarchExecution.AlreadyProcessed>(corpsExecutor(world, ChangeRecorder())
            .advance("corps-$id", 1, target, edges()) { error("reload is not a new turn") })
        nextPhase(world); recorder = ChangeRecorder()
        val blocked = assertIs<CorpsMarchExecution.Applied>(corpsExecutor(world, recorder).advance("corps-$id", 1, target,
            edges(mapOf(resumed.path.edgeIds[resumed.cursor.edgeIndex] to StrategicEdgeState(blockaded = true)))) { error("closed") })
        assertEquals(resumed.cursor, blocked.state.checkpoint.cursor)
        assertEquals(LandMarchStop.EDGE_BLOCKED, blocked.state.checkpoint.stop)
        assertEquals(0L, blocked.movement.spentMm)
        save(world, recorder); world = cold(id)
        repeat(5) {
            if (corpsState(world, 1).checkpoint.stop != LandMarchStop.ARRIVED) {
                nextPhase(world); recorder = ChangeRecorder()
                assertIs<CorpsMarchExecution.Applied>(corpsExecutor(world, recorder)
                    .advance("corps-$id", 1, target, edges()) { LandMarchEntry.CLEAR })
                save(world, recorder); world = cold(id)
            }
        }
        assertEquals(LandMarchStop.ARRIVED, corpsState(world, 1).checkpoint.stop)
        assertEquals(target, world.positionOf(1))
        assertEquals(assignment!!.countyId, world.getGeneralById(1)!!.cityId)
        assertEquals(personalTime, world.getGeneralById(1)!!.turnTime)
        assertEquals(units, world.listBugoks())
    }

    @Test fun `deputy corps preserves owner position and pending encounter after reload and rechecks live cards`() {
        val id = 607; var world = seed(id); var recorder = ChangeRecorder()
        val ownerPosition = world.positionOf(1); val units = world.listBugoks(); val target = destination(world)
        deploy(world, recorder, id, deputy = true)
        val unknown = assertIs<CorpsMarchExecution.Applied>(corpsExecutor(world, recorder)
            .advance("corps-$id", 2, target, edges()) { LandMarchEntry.UNAVAILABLE })
        assertEquals(LandMarchStop.ENCOUNTER_UNAVAILABLE, unknown.state.checkpoint.stop)
        assertEquals(0L, unknown.movement.spentMm)
        save(world, recorder); world = cold(id)
        repeat(5) {
            if (corpsState(world, 2).checkpoint.stop != LandMarchStop.ENCOUNTER) {
                nextPhase(world); recorder = ChangeRecorder()
                assertIs<CorpsMarchExecution.Applied>(corpsExecutor(world, recorder)
                    .advance("corps-$id", 2, target, edges()) { LandMarchEntry.ENCOUNTER })
                save(world, recorder); world = cold(id)
            }
        }
        assertEquals(LandMarchStop.ENCOUNTER, corpsState(world, 2).checkpoint.stop)
        assertNotEquals(ownerPosition, world.positionOf(2))
        assertEquals(ownerPosition, world.positionOf(1)); assertEquals(units, world.listBugoks())
        nextPhase(world)
        assertEquals(CorpsMarchFailure.BATTLE_PENDING, assertIs<CorpsMarchExecution.Rejected>(corpsExecutor(world, ChangeRecorder())
            .advance("corps-$id", 2, target, edges()) { error("pending combat") }).reason)
        assertEquals(CorpsMarchFailure.NO_DEPLOYMENT, assertIs<CorpsMarchExecution.Rejected>(corpsExecutor(world, ChangeRecorder())
            .advance("other-order", 2, target, edges()) { error("wrong order") }).reason)
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND id=7", id)
        world = cold(id)
        assertEquals(CorpsMarchFailure.INVALID_DEPLOYMENT, assertIs<CorpsMarchExecution.Rejected>(corpsExecutor(world, ChangeRecorder())
            .advance("corps-$id", 2, target, edges()) { error("changed commander") }).reason)
    }

    @Test fun `corps stale position flush rolls back progress and phase`() {
        val id = 608; seed(id)
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND id=7", id)
        var world = cold(id); var recorder = ChangeRecorder()
        deploy(world, recorder, id); save(world, recorder); world = cold(id)
        val target = destination(world)
        repeat(5) {
            val before = cold(id); recorder = ChangeRecorder()
            val result = assertIs<CorpsMarchExecution.Applied>(corpsExecutor(world, recorder)
                .advance("corps-$id", 1, target, edges()) { LandMarchEntry.CLEAR })
            if (result.movement.reachedNodes.isNotEmpty()) {
                jdbc.update("UPDATE general_spatial_position SET revision=revision+1 WHERE world_id=? AND general_id=1", id)
                assertFailsWith<opensamguk.infra.persistence.StaleGeneralPositionException> { save(world, recorder) }
                val restored = cold(id)
                assertEquals(before.getGeneralById(1), restored.getGeneralById(1))
                assertEquals(before.positionOf(1), restored.positionOf(1))
                assertEquals(before.getState().currentPhase, restored.getState().currentPhase)
                return
            }
            save(world, recorder); world = cold(id); nextPhase(world)
        }
        fail("fixture must reach a province within five phases")
    }

    /** Explicit synthetic authority preconditions; this is not an inferred production clearance. */
    private fun personalMarchFixture(id: Int, passage: Boolean = true, reactions: Boolean = true): InMemoryTurnWorld {
        seed(id)
        val authority = linkedMapOf<String, Any>()
        if (passage) authority[HwihaLandPassageState.META_KEY] = HwihaLandPassageState.initialMetaValue(topology)
        if (reactions) authority[HwihaMarchReactions.META_KEY] = HwihaMarchReactions.Empty.toMetaValue()
        jdbc.update("UPDATE world_state SET meta=meta || ?::jsonb WHERE id=?",
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(authority), id)
        jdbc.update("UPDATE general SET turn_time='0300-01-01T00:00:00Z' WHERE world_id=? AND id<>1", id)
        // Exclude seed enlistment/dispatch logs from the lifecycle observation window.
        jdbc.update("DELETE FROM log_entry WHERE world_id=?", id)
        return cold(id)
    }

    private fun personalMarchLogs(id: Int): List<String> = jdbc.queryForList(
        "SELECT text FROM log_entry WHERE world_id=? AND general_id=1 AND scope='GENERAL' AND category='ACTION' ORDER BY id",
        String::class.java, id)

    @Test fun `empty personal input advances assignment through real lifecycle and cold reload only once per phase`() {
        val id = 631; var world = personalMarchFixture(id)
        val before = world.getGeneralById(1)!!
        val units = world.listBugoks(); val cards = world.listRetainers()
        val target = destination(world)
        val published = mutableListOf<String>()
        val due = java.time.Instant.parse("0200-01-02T00:00:00Z")
        val first = fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true)
            .runDueGeneralTurns(due).handled.single()
        assertIs<HwihaTurnOutcome.NoAction>(first.hwihaOutcome)
        assertNull(first.requestId)
        world = cold(id)
        val firstState = stored(world)
        val traversed = firstState.path.edgeIds.take(firstState.cursor.edgeIndex).sumOf { metrics.edgesById.getValue(it).costMm }
        assertEquals(30_000_000L, traversed + firstState.cursor.paidMm)
        assertEquals(listOf("발령지로 행군하고 있습니다."), personalMarchLogs(id))
        val afterFirst = world.getGeneralById(1)
        assertTrue(fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true)
            .runDueGeneralTurns(due).handled.isEmpty())
        assertEquals(afterFirst, cold(id).getGeneralById(1))
        assertEquals(firstState.toMetaValue(), stored(cold(id)).toMetaValue())
        assertEquals(1, personalMarchLogs(id).size)
        repeat(5) {
            if (stored(world).stop != LandMarchStop.ARRIVED) {
                nextPhase(world)
                val handled = fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true)
                    .runDueGeneralTurns(due).handled.single()
                assertIs<HwihaTurnOutcome.NoAction>(handled.hwihaOutcome)
                world = cold(id)
            }
        }
        assertEquals(LandMarchStop.ARRIVED, stored(world).stop)
        assertEquals(target, world.positionOf(1))
        val after = world.getGeneralById(1)!!
        assertEquals(stored(world).assignment.countyId, after.cityId)
        assertEquals(before.gold, after.gold); assertEquals(before.rice, after.rice); assertEquals(before.crew, after.crew)
        assertEquals(cards, world.listRetainers()); assertEquals(units, world.listBugoks())
        assertEquals(1, personalMarchLogs(id).count { it == "발령지에 도착했습니다." })
        assertTrue(personalMarchLogs(id).none { "휴식" in it || "실패" in it })
        assertTrue(published.isEmpty())
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=?", Int::class.java, id))
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general_turn WHERE world_id=?", Int::class.java, id))
    }

    @Test fun `personal lifecycle missing passage or reaction authority stops and persists private explanation`() {
        for ((id, passage) in listOf(632 to false, 633 to true)) {
            var world = personalMarchFixture(id, passage = passage, reactions = false)
            val position = world.positionOf(1); val units = world.listBugoks(); val cards = world.listRetainers()
            val before = world.getGeneralById(1)!!
            val published = mutableListOf<String>()
            val due = java.time.Instant.parse("0200-01-02T00:00:00Z")
            val result = fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true)
                .runDueGeneralTurns(due).handled.single()
            assertIs<HwihaTurnOutcome.NoAction>(result.hwihaOutcome)
            world = cold(id)
            assertEquals(position, world.positionOf(1))
            val march = HwihaMarchState.read(world.getGeneralById(1)!!.meta, topology, metrics)
            if (passage) {
                assertNotNull(march)
                assertEquals(LandMarchStop.ENCOUNTER_UNAVAILABLE, march.stop)
                assertEquals(0, march.cursor.edgeIndex); assertEquals(0L, march.cursor.paidMm)
            } else assertNull(march)
            val expected = if (passage) "진입할 지역의 군사·반응 상태를 확인할 수 없어 부임 행군을 멈췄습니다."
                else "육상 통행 상태를 확인할 수 없어 부임 행군을 멈췄습니다."
            assertEquals(listOf(expected), personalMarchLogs(id))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM log_entry WHERE world_id=? AND scope<>'GENERAL'", Int::class.java, id))
            assertEquals(before.gold, world.getGeneralById(1)!!.gold)
            assertEquals(before.rice, world.getGeneralById(1)!!.rice)
            assertEquals(units, world.listBugoks()); assertEquals(cards, world.listRetainers())
            assertTrue(fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true)
                .runDueGeneralTurns(due).handled.isEmpty())
            assertEquals(listOf(expected), personalMarchLogs(id))
            assertTrue(published.isEmpty())
        }
    }

    @Test fun `personal movement position conflict rolls back time phase progress and private log`() {
        val id = 634; var world = personalMarchFixture(id)
        val due = java.time.Instant.parse("0200-01-02T00:00:00Z")
        val published = mutableListOf<String>()
        fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true).runDueGeneralTurns(due)
        world = cold(id)
        repeat(5) {
            val checkpoint = stored(world)
            val nextCost = metrics.edgesById.getValue(checkpoint.path.edgeIds[checkpoint.cursor.edgeIndex]).costMm
            val before = cold(id); val logsBefore = personalMarchLogs(id)
            nextPhase(world)
            if (nextCost - checkpoint.cursor.paidMm <= 30_000_000L) {
                jdbc.update("UPDATE general_spatial_position SET revision=revision+1 WHERE world_id=? AND general_id=1", id)
                assertFailsWith<opensamguk.infra.persistence.StaleGeneralPositionException> {
                    fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true)
                        .runDueGeneralTurns(due)
                }
                val restored = cold(id)
                assertEquals(before.getGeneralById(1), restored.getGeneralById(1))
                assertEquals(before.positionOf(1), restored.positionOf(1))
                assertEquals(before.getState().currentPhase, restored.getState().currentPhase)
                assertEquals(logsBefore, personalMarchLogs(id))
                assertTrue(published.isEmpty())
                return
            }
            fixture.service(opensamguk.common.world.WorldId(id), world, published, movement = true).runDueGeneralTurns(due)
            world = cold(id)
        }
        fail("fixture must reach a province within five phases")
    }

    private fun personalDeploymentFixture(id: Int): InMemoryTurnWorld {
        personalMarchFixture(id)
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND id=7", id)
        return cold(id)
    }
    private fun reserveDeployment(id:Int, world:InMemoryTurnWorld, requestId:String) {
        val envelope=opensamguk.common.wire.TurnDaemonCommandEnvelope(requestId,"0200-01-01T00:00:00Z",
            opensamguk.common.wire.TurnDaemonCommand.Run(opensamguk.common.wire.RunReason.POKE))
        opensamguk.infra.persistence.CommandInboxRepository(NamedParameterJdbcTemplate(jdbc)).insertAccepted(
            opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand(
                opensamguk.common.world.WorldId(id),requestId,commandKind=opensamguk.infra.persistence.CommandInboxRepository.CommandKind.RESERVED_TURN,
                intentFingerprint="a".repeat(64),generalId=1,turnIdx=0,actionCode=HwihaDeployInput.INPUT_ID,
                payloadJson=opensamguk.common.wire.encodeCommandPayload(envelope),ownerUserId=42))
        opensamguk.infra.persistence.ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc)).reserve(
            opensamguk.common.world.WorldId(id),1,0,HwihaDeployInput.INPUT_ID,
            HwihaDeployInput.canonicalJson(DeployInput(1,listOf(7),destination(world))),requestId=requestId)
    }
    private fun runDeploymentTurn(id:Int, world:InMemoryTurnWorld, published:MutableList<String>) =
        fixture.service(opensamguk.common.world.WorldId(id),world,published,movement=true)
            .runDueGeneralTurns(java.time.Instant.parse("0200-01-02T00:00:00Z"))

    @Test fun `personal deployment reservation starts once pauses for other actions and persists through arrival`() {
        val id=641;var world=personalDeploymentFixture(id)
        val personal=world.getGeneralById(1)!!;val units=world.listBugoks();val target=destination(world)
        val published=mutableListOf<String>()
        reserveDeployment(id,world,"personal-deploy-641")
        assertIs<HwihaTurnOutcome.Applied>(runDeploymentTurn(id,world,published).handled.single().hwihaOutcome)
        world=cold(id)
        val order=assertNotNull(HwihaCorpsOrder.read(world.getGeneralById(1)!!.meta,topology))
        assertEquals("personal-deploy-641",order.orderId);assertEquals(target,order.destination)
        var first=corpsState(world,1).checkpoint
        val traveled=first.path.edgeIds.take(first.cursor.edgeIndex).sumOf { metrics.edgesById.getValue(it).costMm }+first.cursor.paidMm
        assertEquals(30_000_000L,traveled)
        assertNull(HwihaMarchState.read(world.getGeneralById(1)!!.meta,topology,metrics))
        assertNotNull(HwihaCountyAssignment.read(world.getGeneralById(1)!!.meta))
        assertTrue(runDeploymentTurn(id,world,published).handled.isEmpty())
        assertEquals(listOf("personal-deploy-641"),published)
        val edgeId=first.path.edgeIds[first.cursor.edgeIndex]
        jdbc.update("UPDATE world_state SET meta=jsonb_set(meta,ARRAY['hwihaLandPassage','edges',?,'blockaded'],'true'::jsonb) WHERE id=?",edgeId,id)
        world=cold(id);nextPhase(world);runDeploymentTurn(id,world,published);world=cold(id)
        assertEquals(LandMarchStop.EDGE_BLOCKED,corpsState(world,1).checkpoint.stop)
        assertEquals(first.cursor,corpsState(world,1).checkpoint.cursor)
        assertEquals(order,HwihaCorpsOrder.read(world.getGeneralById(1)!!.meta,topology))
        first=corpsState(world,1).checkpoint
        jdbc.update("UPDATE world_state SET meta=jsonb_set(meta,ARRAY['hwihaLandPassage','edges',?,'blockaded'],'false'::jsonb) WHERE id=?",edgeId,id)
        world=cold(id)
        opensamguk.infra.persistence.ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc)).reserve(
            opensamguk.common.world.WorldId(id),1,0,"placement.assign","{}",requestId="pause-641")
        nextPhase(world)
        assertIs<HwihaTurnOutcome.Rejected>(runDeploymentTurn(id,world,published).handled.single().hwihaOutcome)
        world=cold(id)
        assertEquals(first.toMetaValue(),corpsState(world,1).checkpoint.toMetaValue())
        reserveDeployment(id,world,"duplicate-deploy-641")
        nextPhase(world)
        assertEquals(DeploymentFailure.ALREADY_DEPLOYED.name,assertIs<HwihaTurnOutcome.Rejected>(
            runDeploymentTurn(id,world,published).handled.single().hwihaOutcome).code)
        world=cold(id)
        assertEquals(first.toMetaValue(),corpsState(world,1).checkpoint.toMetaValue())
        repeat(5) {
            if(corpsState(world,1).checkpoint.stop!=LandMarchStop.ARRIVED) {
                nextPhase(world);runDeploymentTurn(id,world,published);world=cold(id)
            }
        }
        assertEquals(LandMarchStop.ARRIVED,corpsState(world,1).checkpoint.stop)
        assertEquals(target,world.positionOf(1));assertEquals(units,world.listBugoks())
        assertEquals(personal.gold,world.getGeneralById(1)!!.gold);assertEquals(personal.rice,world.getGeneralById(1)!!.rice)
        val deployed=assertNotNull(HwihaDeploymentState.read(world.getGeneralById(1)!!.meta)).corps.single()
        order.requireBinding(deployed,1)
        assertEquals(1,personalMarchLogs(id).count { it=="출병 목적지에 도착했습니다." })
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=? AND request_id='personal-deploy-641'",Int::class.java,id))
    }

    @Test fun `changed commander rejects reserved departure without partial deployment or destination`() {
        val id=642;var world=personalDeploymentFixture(id);val position=world.positionOf(1)
        reserveDeployment(id,world,"changed-deploy-642")
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=4 WHERE world_id=? AND id=7",id)
        world=cold(id)
        val result=assertIs<HwihaTurnOutcome.Rejected>(runDeploymentTurn(id,world,mutableListOf()).handled.single().hwihaOutcome)
        assertEquals(DeploymentFailure.COMMANDER_CHANGED.name,result.code)
        world=cold(id)
        assertNull(HwihaDeploymentState.read(world.getGeneralById(1)!!.meta))
        assertNull(HwihaCorpsOrder.read(world.getGeneralById(1)!!.meta,topology))
        assertEquals(position,world.positionOf(1))
    }

    @Test fun `corrupt durable destination never resumes or replaces the active corps order`() {
        val id=643;var world=personalDeploymentFixture(id);val published=mutableListOf<String>()
        reserveDeployment(id,world,"bound-deploy-643");runDeploymentTurn(id,world,published)
        world=cold(id);val position=world.positionOf(1);val checkpoint=corpsState(world,1).checkpoint.toMetaValue()
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{hwihaCorpsOrder,orderId}','\"wrong-order\"'::jsonb) WHERE world_id=? AND id=1",id)
        world=cold(id);nextPhase(world);runDeploymentTurn(id,world,published);world=cold(id)
        assertEquals(position,world.positionOf(1));assertEquals(checkpoint,corpsState(world,1).checkpoint.toMetaValue())
        assertTrue(personalMarchLogs(id).any { it=="출병 명령 상태를 확인할 수 없어 행군을 멈췄습니다." })
        assertEquals("wrong-order",HwihaCorpsOrder.read(world.getGeneralById(1)!!.meta,topology)!!.orderId)
    }

    @Test fun `new deployment position conflict rolls back corps order result and consumed reservation`() {
        val id=644;var world=personalDeploymentFixture(id)
        reserveDeployment(id,world,"rollback-deploy-644")
        world=cold(id);val before=world.getGeneralById(1)
        jdbc.update("UPDATE general_spatial_position SET revision=revision+1 WHERE world_id=? AND general_id=1",id)
        val published=mutableListOf<String>()
        assertFailsWith<opensamguk.infra.persistence.StaleGeneralPositionException> { runDeploymentTurn(id,world,published) }
        val restored=cold(id)
        assertEquals(before,restored.getGeneralById(1));assertEquals(world.getState().currentPhase,restored.getState().currentPhase)
        assertTrue(personalMarchLogs(id).isEmpty());assertTrue(published.isEmpty())
        assertEquals("rollback-deploy-644",opensamguk.infra.persistence.ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc))
            .readReserved(opensamguk.common.world.WorldId(id),1,0).requestId)
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=?",Int::class.java,id))
    }

    @Test fun `reservation ownership transfer or missing original submitter denies departure`() {
        for(id in listOf(645,646)) {
            var world=personalDeploymentFixture(id);reserveDeployment(id,world,"owner-deploy-$id")
            if(id==645) jdbc.update("UPDATE general SET user_id=99 WHERE world_id=? AND id=1",id)
            else jdbc.update("UPDATE command_inbox SET owner_user_id=NULL WHERE world_id=?",id)
            world=cold(id);val position=world.positionOf(1)
            assertEquals("FORBIDDEN",assertIs<HwihaTurnOutcome.Rejected>(runDeploymentTurn(id,world,mutableListOf()).handled.single().hwihaOutcome).code)
            world=cold(id)
            assertNull(HwihaDeploymentState.read(world.getGeneralById(1)!!.meta))
            assertNull(HwihaCorpsOrder.read(world.getGeneralById(1)!!.meta,topology))
            assertEquals(position,world.positionOf(1))
        }
    }

    private fun encounterFixture(id: Int, defenderIds: List<Int> = listOf(100, 101)): InMemoryTurnWorld {
        var world = personalDeploymentFixture(id)
        val source = world.positionOf(1)!!
        val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(topology,
            StrategicPathRequest(source, destination(world), 1),
            HwihaLandPassageState.read(world.getState().meta, topology)!!, metrics)).path
        val province = path.nodeKeys[1].removePrefix("land:")
        assertTrue(metrics.edgesById.getValue(path.edgeIds.first()).costMm <= LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        for (enemyId in defenderIds) {
            jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,officer_level,gold,rice,
                crew,leadership,strength,intel,politics,charm,turn_time,last_turn,meta)
                SELECT world_id,?,'encounter-fixture',0,city_id,2,0,1000,2000,100,70,70,70,70,70,
                '0300-01-01T00:00:00Z',last_turn,meta || '{"hwihaLord":true}'::jsonb
                FROM general WHERE world_id=? AND id=2""", enemyId, id)
            jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
                VALUES (?,?,?,?,'LAND_PROVINCE',?,1)""", id, enemyId, topology.topologyRevision, topology.contentHash, province)
            jdbc.update("""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,training,morale,provisions)
                VALUES (?,?,?,'encounter-fixture',100,1,50,50,200)""", id, 1000 + enemyId, enemyId)
        }
        world = cold(id)
        val recorder = ChangeRecorder()
        for (enemyId in defenderIds) {
            val orderId = "enemy-$id-$enemyId"
            assertIs<DeploymentExecution.Applied>(HwihaDeploymentExecutor(world,recorder,topology,metrics)
                .deploy(orderId,DeploymentRequest(enemyId,null,listOf(1000 + enemyId))))
            val before = world.getGeneralById(enemyId)!!
            val order = HwihaCorpsOrder(orderId,enemyId,enemyId,StrategicNodeRef.LandProvince(province),
                topology.topologyRevision,topology.contentHash)
            val after = before.copy(meta=before.meta+(HwihaCorpsOrder.META_KEY to order.toMetaValue()))
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before),PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
        }
        save(world,recorder)
        return cold(id)
    }

    @Test fun `real personal entry captures all hostile corps and locks both sides after cold reload`() {
        val id=647;var world=encounterFixture(id)
        val units=world.listBugoks();val published=mutableListOf<String>()
        reserveDeployment(id,world,"encounter-$id")
        assertIs<HwihaTurnOutcome.Applied>(runDeploymentTurn(id,world,published).handled.single().hwihaOutcome)
        world=cold(id)
        val encounter=assertNotNull(HwihaCorpsEncounter.read(world.getGeneralById(1)!!.meta,topology))
        assertEquals(listOf(100,101),encounter.defenders.map { it.commanderGeneralId })
        assertEquals("encounter-$id",encounter.attacker.orderId)
        val projection=assertNotNull(HwihaDeploymentExecutor(world,ChangeRecorder(),topology,metrics).projection())
        for (commander in listOf(1,100,101)) {
            assertEquals(encounter.toMetaValue(),HwihaCorpsEncounter.read(world.getGeneralById(commander)!!.meta,topology)?.toMetaValue())
            assertEquals(HwihaEncounterDeployment.Result.InsufficientDefenderCapacity,
                HwihaEncounterDeployment.read(world.getGeneralById(commander)!!.meta, encounter, bundle.provinceCells))
            assertEquals(HwihaEncounterDeployment.defaultMetaValue(encounter,bundle.provinceCells),
                world.getGeneralById(commander)!!.meta[HwihaEncounterDeployment.META_KEY])
            val forces=assertNotNull(HwihaEncounterForces.read(world.getGeneralById(commander)!!.meta,encounter))
            assertEquals(listOf(7,1100,1101),forces.units.map { it.bugokId })
            for (unit in forces.units) {
                val original=units.single { it.id==unit.bugokId }
                assertEquals(listOf(original.troops,original.crewTypeId,original.training,original.morale,original.fatigue,original.provisions),
                    listOf(unit.troops,unit.crewTypeId,unit.training,unit.morale,unit.fatigue,unit.provisions))
                assertEquals(original.masterGeneralId,unit.ownerGeneralId)
                assertEquals(original.commanderRetainerId,unit.commanderRetainerId)
            }
            assertEquals(listOf(1,100,101),forces.commanders.map { it.generalId })
            assertEquals(HwihaBattlePlans.defaultFor(encounter).toMetaValue(),
                HwihaBattlePlans.read(world.getGeneralById(commander)!!.meta,encounter)?.toMetaValue())
            val combat=assertNotNull(HwihaEncounterCombatProfiles.read(world.getGeneralById(commander)!!.meta,forces,HwihaUnitProfilesJson.loadDefault()))
            assertFalse(combat.ready)
            assertTrue(combat.unavailable.any { it.crewTypeId==1 && it.reason==HwihaEncounterCombatProfiles.Reason.UNKNOWN_CREW_TYPE })
            val relations=assertNotNull(HwihaEncounterRelations.read(world.getGeneralById(commander)!!.meta,encounter))
            assertEquals(3,relations.pairs.size)
            assertTrue(relations.pairs.all { it.hostile })
            assertEquals(world.getGeneralById(1)!!.meta[HwihaEncounterForces.META_KEY],forces.toMetaValue())
            assertEquals(world.getGeneralById(1)!!.meta[HwihaEncounterRelations.META_KEY],relations.toMetaValue())
            assertEquals(encounter.province,world.positionOf(commander))
            assertTrue(projection.people.single { it.id==commander }.inBattle)
        }
        assertEquals(units,world.listBugoks())
        // A different friendly general cannot enroll the already locked hostile corps into another event.
        assertNull(HwihaCorpsEncounterRecorder(world,ChangeRecorder(),topology,metrics,bundle.provinceCells).defendersAt(2,encounter.province))
        val before=world.listGenerals().associate { it.id to world.positionOf(it.id) }
        jdbc.update("UPDATE world_state SET current_phase=current_phase+1 WHERE id=?",id)
        jdbc.update("UPDATE general SET turn_time='0200-01-01T00:00:00Z' WHERE world_id=? AND id IN (100,101)",id)
        world=cold(id);runDeploymentTurn(id,world,published);world=cold(id)
        assertEquals(before,world.listGenerals().associate { it.id to world.positionOf(it.id) })
        assertEquals(encounter.toMetaValue(),HwihaCorpsEncounter.read(world.getGeneralById(1)!!.meta,topology)?.toMetaValue())
        assertEquals(listOf("encounter-$id"),published)
        jdbc.update("UPDATE general SET meta=meta-'hwihaCorpsEncounter' WHERE world_id=? AND id=100",id)
        assertNull(HwihaDeploymentExecutor(cold(id),ChangeRecorder(),topology,metrics).projection())
    }

    @Test fun `real personal entry seals occupied cells and detects stored placement tampering`() {
        val id=649;var world=encounterFixture(id,listOf(100))
        jdbc.update("UPDATE general_bugok SET crew_type_id=1100 WHERE world_id=?",id)
        world=cold(id)
        val units=world.listBugoks();val published=mutableListOf<String>()
        reserveDeployment(id,world,"encounter-$id")
        assertIs<HwihaTurnOutcome.Applied>(runDeploymentTurn(id,world,published).handled.single().hwihaOutcome)
        world=cold(id)
        val encounter=assertNotNull(HwihaCorpsEncounter.read(world.getGeneralById(1)!!.meta,topology))
        val expected=HwihaEncounterDeployment.defaultMetaValue(encounter,bundle.provinceCells)
        for (commander in listOf(1,100)) {
            val meta=world.getGeneralById(commander)!!.meta
            val deployment=assertIs<HwihaEncounterDeployment.Result.Ready>(
                HwihaEncounterDeployment.read(meta,encounter,bundle.provinceCells)).deployment
            assertEquals(expected,meta[HwihaEncounterDeployment.META_KEY])
            assertEquals(listOf(7,1100),deployment.tokens.map { it.bugokId }.sorted())
            assertEquals(2,deployment.tokens.mapNotNull { it.position }.distinct().size)
            val forces=assertNotNull(HwihaEncounterForces.read(meta,encounter))
            val combat=assertNotNull(HwihaEncounterCombatProfiles.read(meta,forces,HwihaUnitProfilesJson.loadDefault()))
            assertTrue(combat.ready)
            assertEquals(listOf(1100),combat.profiles.map { it.crewTypeId })
            assertEquals(world.getGeneralById(1)!!.meta[HwihaEncounterCombatProfiles.META_KEY],combat.toMetaValue())
        }
        assertEquals(units,world.listBugoks())
        assertEquals(listOf("encounter-$id"),published)
        val storedPlans=assertNotNull(HwihaBattlePlans.read(world.getGeneralById(1)!!.meta,encounter)).toMetaValue()
        assertEquals(storedPlans,HwihaBattlePlans.read(world.getGeneralById(100)!!.meta,encounter)?.toMetaValue())
        jdbc.update("""UPDATE general SET meta=jsonb_set(meta,
            '{hwihaBattlePlans,plans,0,commands,0,threshold}','51'::jsonb) WHERE world_id=? AND id=1""",id)
        world=cold(id)
        assertFailsWith<IllegalArgumentException> { HwihaBattlePlans.read(world.getGeneralById(1)!!.meta,encounter) }
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{hwihaBattlePlans}',?::jsonb) WHERE world_id=? AND id=1",
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(storedPlans),id)
        world=cold(id)
        val storedCombat=world.getGeneralById(1)!!.meta[HwihaEncounterCombatProfiles.META_KEY]
        jdbc.update("""UPDATE general SET meta=jsonb_set(meta,
            '{hwihaEncounterCombatProfiles,profiles,0,attackPower}','101'::jsonb) WHERE world_id=? AND id=1""",id)
        world=cold(id)
        assertFailsWith<IllegalArgumentException> {
            HwihaEncounterCombatProfiles.read(world.getGeneralById(1)!!.meta,
                HwihaEncounterForces.read(world.getGeneralById(1)!!.meta,encounter)!!,HwihaUnitProfilesJson.loadDefault())
        }
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{hwihaEncounterCombatProfiles}',?::jsonb) WHERE world_id=? AND id=1",
            com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(storedCombat),id)
        world=cold(id)
        val frozen=assertNotNull(HwihaEncounterForces.read(world.getGeneralById(1)!!.meta,encounter)).toMetaValue()
        val relations=assertNotNull(HwihaEncounterRelations.read(world.getGeneralById(1)!!.meta,encounter)).toMetaValue()
        jdbc.update("UPDATE general_bugok SET morale=morale-1,provisions=provisions+1 WHERE world_id=? AND id=7",id)
        jdbc.update("UPDATE general SET leadership=leadership+1 WHERE world_id=? AND id=1",id)
        world=cold(id)
        val snapshot=assertNotNull(HwihaEncounterForces.read(world.getGeneralById(1)!!.meta,encounter))
        assertEquals(frozen,snapshot.toMetaValue())
        assertNotEquals(world.listBugoks().single { it.id==7 }.morale,snapshot.units.single { it.bugokId==7 }.morale)
        assertNotEquals(world.getGeneralById(1)!!.stats.leadership,snapshot.commanders.single { it.generalId==1 }.leadership)
        assertEquals(relations,HwihaEncounterRelations.read(world.getGeneralById(1)!!.meta,encounter)?.toMetaValue())
        jdbc.update("""UPDATE general SET meta=jsonb_set(meta,
            '{hwihaEncounterForces,units,0,troops}','1'::jsonb) WHERE world_id=? AND id=1""",id)
        world=cold(id)
        assertFailsWith<IllegalArgumentException> { HwihaEncounterForces.read(world.getGeneralById(1)!!.meta,encounter) }
        jdbc.update("""UPDATE general SET meta=jsonb_set(meta,
            '{hwihaEncounterDeployment,tokens,0,position}','null'::jsonb) WHERE world_id=? AND id=1""",id)
        world=cold(id)
        assertFailsWith<IllegalArgumentException> {
            HwihaEncounterDeployment.read(world.getGeneralById(1)!!.meta,encounter,bundle.provinceCells)
        }
    }

    @Test fun `position conflict rolls back encounter records on every participant`() {
        val id=648;var world=encounterFixture(id)
        reserveDeployment(id,world,"encounter-rollback-$id");world=cold(id)
        val before=listOf(1,100,101).associateWith { world.getGeneralById(it) }
        jdbc.update("UPDATE general_spatial_position SET revision=revision+1 WHERE world_id=? AND general_id=1",id)
        val published=mutableListOf<String>()
        assertFailsWith<opensamguk.infra.persistence.StaleGeneralPositionException> { runDeploymentTurn(id,world,published) }
        world=cold(id)
        for ((generalId,general) in before) assertEquals(general,world.getGeneralById(generalId))
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM command_result WHERE world_id=?",Int::class.java,id))
        assertTrue(published.isEmpty())
    }

}
