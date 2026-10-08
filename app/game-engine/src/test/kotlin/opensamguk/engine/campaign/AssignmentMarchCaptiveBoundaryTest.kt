package opensamguk.engine.campaign

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.EngineGeneralActionPipelineBuilder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.CountyAssignment
import opensamguk.logic.input.DeployInput
import opensamguk.logic.input.DeployInputs
import opensamguk.logic.input.DispatchAssessment
import opensamguk.logic.input.DispatchInput
import opensamguk.logic.input.DispatchReplyInput
import opensamguk.logic.input.DispatchReplyRequest
import opensamguk.logic.input.DispatchRequest
import opensamguk.logic.input.DispatchState
import opensamguk.logic.input.DispatchStatus
import opensamguk.logic.input.QueuedDispatch
import opensamguk.logic.input.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Accepted player dispatches survive real capture without authorizing an escape march. */
class AssignmentMarchCaptiveBoundaryTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()

    private fun world(): Pair<InMemoryTurnWorld, ChangeRecorder> {
        val issuer = fixture.person(1, 1, route.startCity, userId = "42").copy(npcState = 0)
        val target = fixture.person(2, 1, route.startCity, userId = "43", lord = false).copy(
            npcState = 0, turnTime = issuer.turnTime.plusSeconds(60))
        val attacker = fixture.person(100, 2, route.startCity).copy(
            turnTime = issuer.turnTime.plusSeconds(120))
        return fixture.world(listOf(issuer to route.start, target to route.first, attacker to route.start),
            bugoks = listOf(fixture.unit(7, target.id, 1), fixture.unit(1100, attacker.id, 1000)),
            retainers = listOf(Retainer(1, issuer.id, "EXISTING", target.id, target.name, "guest")),
            cityChanges = { city -> if (city.id in setOf(route.startCity, route.destinationCounty))
                city.copy(nationId = 1) else city }) to ChangeRecorder()
    }

    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        ReservedTurnHandler(world,
            EngineGeneralActionPipelineBuilder(world, 200).registryFor(world.getGeneralById(1)!!), "fixture", 200,
            recorder = recorder, deploymentContext = fixture.topology to fixture.metrics,
            provinceCells = fixture.cells)

    private fun due(world: InMemoryTurnWorld, handler: ReservedTurnHandler, recorder: ChangeRecorder, id: Int) {
        val time = world.getGeneralById(id)!!.turnTime.plusSeconds(1)
        val turns = TurnDaemonLifecycle(world, handler,
            movementOf = { actor, reserved, outcome -> fixture.movement(world, recorder).onTurn(actor, reserved, outcome) },
            reservedActionOf = { CampaignWorldFixture.NO_INPUT }).runTick(time)
        assertEquals(listOf(id), turns.map { it.generalId }, "only the requested player is due")
        assertIs<TurnOutcome.NoAction>(turns.single().inputOutcome)
    }

    private fun accepted(world: InMemoryTurnWorld, recorder: ChangeRecorder): ReservedTurnHandler {
        val handler = handler(world, recorder)
        assertFalse(CaptiveState.META_KEY in world.getGeneralById(2)!!.meta)
        assertNull(CountyAssignment.read(world.getGeneralById(2)!!.meta))
        val request = DispatchRequest(1, 2, route.destinationCounty)
        val queued = handler.courtHandler.handle(TurnDaemonCommand.ImmediateInput(
            "assignment-before-capture", 1, 42, "court.dispatch", DispatchInput.canonicalJson(request)))
        assertTrue(queued.ok, "a free player lord must lawfully queue dispatch: $queued")
        assertNotNull(QueuedDispatch.read(world.getGeneralById(1)!!.meta))
        assertNull(DispatchState.read(world.getGeneralById(2)!!.meta))

        due(world, handler, recorder, 1)

        val execution = handler.courtHandler.takeExecutions().single()
        assertTrue(execution.result.ok, "the actual issuer turn must issue dispatch: $execution")
        assertEquals("assignment-before-capture", execution.requestId)
        assertEquals(42, execution.ownerUserId)
        assertNull(QueuedDispatch.read(world.getGeneralById(1)!!.meta))
        val pending = assertNotNull(DispatchState.read(world.getGeneralById(2)!!.meta))
        assertEquals(DispatchStatus.PENDING, pending.status)
        val reply = handler.courtHandler.handle(TurnDaemonCommand.ImmediateInput(
            "assignment-accept", 2, 43, "court.dispatchReply",
            DispatchReplyInput.canonicalJson(DispatchReplyRequest(2, pending.dispatchId, true))))
        assertTrue(reply.ok, "the actual player recipient must accept dispatch: $reply")
        assertEquals(DispatchStatus.ACCEPTED, DispatchState.read(world.getGeneralById(2)!!.meta)?.status)
        val assignment = assertNotNull(CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertEquals(pending.dispatchId, assignment.dispatchId)
        assertEquals(1, assignment.issuerId)
        assertEquals(1, assignment.nationId)
        assertEquals(route.destinationCounty, assignment.countyId)
        assertIs<DispatchAssessment.Eligible>(DispatchExecutor(world, recorder).assessAssignment(2, assignment))
        assertEquals(route.first, world.positionOf(2))
        return handler
    }

    @Test
    fun `a free player recipient still marches after actual queued dispatch and acceptance`() {
        val (world, recorder) = world()
        val handler = accepted(world, recorder)
        val assignment = CountyAssignment.read(world.getGeneralById(2)!!.meta)
        fixture.nextPhase(world)

        due(world, handler, recorder, 2)

        assertEquals(route.destination, world.positionOf(2))
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertNull(CaptiveState.read(world.getGeneralById(2)!!.meta))
        assertEquals("43", world.getGeneralById(2)!!.userId)
    }

    private fun captured(): Triple<InMemoryTurnWorld, ReservedTurnHandler, ChangeRecorder> {
        val (world, recorder) = world()
        val handler = accepted(world, recorder)
        val assignment = assertNotNull(CountyAssignment.read(world.getGeneralById(2)!!.meta))
        val deploy = DeployHandler(world, recorder, fixture.topology, fixture.metrics)
        assertIs<TurnOutcome.Applied>(deploy.handle(2,
            DeployInputs.canonicalJson(DeployInput(2, listOf(7), route.first)), "recipient-self-corps", 43))
        assertIs<TurnOutcome.Applied>(deploy.handle(100,
            DeployInputs.canonicalJson(DeployInput(100, listOf(1100), route.destination)), null, null,
            npcSelected = true))
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(100, CampaignWorldFixture.NO_INPUT)
        assertNotNull(CorpsEncounter.read(world.getGeneralById(2)!!.meta, fixture.topology))
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(100, CampaignWorldFixture.NO_INPUT)
        val captive = assertNotNull(CaptiveState.read(world.getGeneralById(2)!!.meta),
            "the actual battle producer must capture the human recipient")
        assertEquals(100, captive.captorGeneralId)
        assertEquals(route.first.id, captive.heldProvinceId)
        assertEquals(world.positionOf(100), world.positionOf(2))
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertEquals("43", world.getGeneralById(2)!!.userId)
        return Triple(world, handler, recorder)
    }

    @Test
    fun `actual capture prevents accepted player assignment from escaping its held province on no input`() {
        val (world, handler, recorder) = captured()
        val assignment = assertNotNull(CountyAssignment.read(world.getGeneralById(2)!!.meta))
        val captive = assertNotNull(CaptiveState.read(world.getGeneralById(2)!!.meta))
        val position = world.generalPositionSnapshot()!!.stateFor(2)
        val captor = world.getGeneralById(100)
        val captorPosition = world.positionOf(100)
        val positionWrites = recorder.generalPositionWrites()
        fixture.nextPhase(world)

        due(world, handler, recorder, 2)

        assertEquals(position, world.generalPositionSnapshot()!!.stateFor(2),
            "captivity must prevent automatic assignment movement outside heldProvince")
        assertEquals(captorPosition, world.positionOf(2))
        assertEquals(captor, world.getGeneralById(100), "the not-due captor must remain unchanged")
        assertEquals(captive, CaptiveState.read(world.getGeneralById(2)!!.meta))
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertEquals(positionWrites, recorder.generalPositionWrites())
        assertMarchFailure(world, "CAPTIVE")
        assertTrue(world.peekLogs().any { it.generalId == 2 && it.text == "구금 중이므로 부임 행군을 멈췄습니다." })
        assertStoredMetadata(world, recorder)
        fixture.movement(world, recorder).onTurn(2, CampaignWorldFixture.NO_INPUT)
        assertEquals(position, world.generalPositionSnapshot()!!.stateFor(2))
        assertEquals(positionWrites, recorder.generalPositionWrites())
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertEquals(captive, CaptiveState.read(world.getGeneralById(2)!!.meta))
    }

    @Test
    fun `a malformed captive marker on an actually captured recipient still prevents movement`() {
        val (world, handler, recorder) = captured()
        val actor = world.getGeneralById(2)!!
        world.updateGeneral(actor.copy(meta = actor.meta + (CaptiveState.META_KEY to "malformed")))
        val position = world.generalPositionSnapshot()!!.stateFor(2)
        val assignment = CountyAssignment.read(actor.meta)
        val positionWrites = recorder.generalPositionWrites()
        fixture.nextPhase(world)

        due(world, handler, recorder, 2)

        assertEquals(position, world.generalPositionSnapshot()!!.stateFor(2))
        assertEquals(positionWrites, recorder.generalPositionWrites())
        assertEquals("malformed", world.getGeneralById(2)!!.meta[CaptiveState.META_KEY])
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertMarchFailure(world, "CAPTIVE")
        assertStoredMetadata(world, recorder)
    }

    @Test
    fun `changed retainer authority still rejects an actually captured recipient before captivity`() {
        val (world, handler, recorder) = captured()
        val actor = world.getGeneralById(2)!!
        val position = world.generalPositionSnapshot()!!.stateFor(2)
        val positionWrites = recorder.generalPositionWrites()
        world.removeRetainer(1)
        fixture.nextPhase(world)

        due(world, handler, recorder, 2)

        assertMarchFailure(world, "INVALID_ASSIGNMENT")
        assertEquals(position, world.generalPositionSnapshot()!!.stateFor(2))
        assertEquals(positionWrites, recorder.generalPositionWrites())
        assertEquals(CountyAssignment.read(actor.meta), CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertEquals(CaptiveState.read(actor.meta), CaptiveState.read(world.getGeneralById(2)!!.meta))
        assertStoredMetadata(world, recorder)
    }

    @Test
    fun `removing captive metadata permits the already accepted assignment to resume`() {
        val (world, handler, recorder) = captured()
        val actor = world.getGeneralById(2)!!
        val assignment = CountyAssignment.read(actor.meta)
        // This is a restored FREE-state control, not a test of the release input producer.
        world.updateGeneral(actor.copy(meta = actor.meta - CaptiveState.META_KEY))
        fixture.nextPhase(world)

        due(world, handler, recorder, 2)

        assertEquals(route.destination, world.positionOf(2))
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(2)!!.meta))
        assertNull(CaptiveState.read(world.getGeneralById(2)!!.meta))
        assertStoredMetadata(world, recorder)
    }

    private fun assertMarchFailure(world: InMemoryTurnWorld, expected: String) {
        val log = world.peekLogs().last { it.generalId == 2 && it.eventKind == RecordKind.MARCH_ASSIGNMENT }
        val refs = assertIs<Map<*, *>>(log.meta?.get(RecordKind.REFS_META_KEY))
        assertEquals(expected, refs["failure"])
        assertEquals("assignment-before-capture", refs["dispatchId"])
        assertEquals(route.destinationCounty, refs["countyId"])
    }

    private fun assertStoredMetadata(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        val actor = world.getGeneralById(2)!!
        assertEquals(actor.meta, payload.updatedGenerals.single { it.id == 2 }.meta)
        assertEquals(recorder.generalPositionWrites(), payload.generalPositionWrites)
    }
}
