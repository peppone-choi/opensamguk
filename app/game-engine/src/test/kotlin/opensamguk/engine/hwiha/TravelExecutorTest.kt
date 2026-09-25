package opensamguk.engine.hwiha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.TravelInput
import opensamguk.logic.input.TravelRequest
import opensamguk.logic.input.TravelState
import opensamguk.logic.input.PersonalTravelCondition
import opensamguk.logic.input.CountyAssignment
import opensamguk.logic.input.MarchState
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.LandMarchStop
import opensamguk.logic.input.Phase
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import opensamguk.engine.turn.PerTurnOverlay

class TravelExecutorTest {
    @Test
    fun `direct travel pays each edge over successive phases and replays once`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(101, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val executor = TravelExecutor(world, recorder, fixture.topology, fixture.metrics)
        val request = TravelRequest(actor.id, TravelInput.MOVE, route.destination)
        val partial = assertIs<TravelExecution.Applied>(
            executor.start("travel-101", request, route.destination, 1) { LandMarchEntry.CLEAR })
        val firstCost = fixture.metrics.edgesById.getValue(partial.state.checkpoint.path.edgeIds.first()).costMm
        assertEquals(LandMarchStop.BUDGET_EXHAUSTED, partial.state.checkpoint.stop)
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(0, partial.movement.reachedNodes.size)
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.start("travel-101", request, route.destination, 1) { LandMarchEntry.CLEAR })
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.start("travel-102", request, route.destination, 1) { LandMarchEntry.CLEAR })

        fixture.nextPhase(world)
        val reached = assertIs<TravelExecution.Applied>(
            executor.resume(actor.id, firstCost - 1) { LandMarchEntry.CLEAR })
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(listOf(route.first), reached.movement.reachedNodes)
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.resume(actor.id, firstCost) { LandMarchEntry.CLEAR })
        assertEquals("travel-101", TravelState.read(world.getGeneralById(actor.id)!!.meta,
            fixture.topology, fixture.metrics)?.orderId)
    }

    @Test
    fun `forced march persists personal cost once on the recorder path`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(102, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val request = TravelRequest(actor.id, TravelInput.FORCED_MARCH, route.destination)
        val first = assertIs<TravelExecution.Applied>(
            executor.start("forced-102", request, route.destination, 45_000_000) { LandMarchEntry.CLEAR })
        val condition = PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta)
        assertEquals(first.condition, condition)
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.start("forced-102", request, route.destination, 45_000_000) { LandMarchEntry.CLEAR })
        assertEquals(condition, PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
    }

    @Test
    fun `direct movement clears an aligned assignment march before the next projection`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(103, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val executor = TravelExecutor(world, recorder, fixture.topology, fixture.metrics)
        val request = TravelRequest(actor.id, TravelInput.MOVE, route.destination)
        val initial = assertIs<TravelExecution.Applied>(
            executor.start("travel-103", request, route.destination, 1) { LandMarchEntry.CLEAR })
        val before = world.getGeneralById(actor.id)!!
        val march = MarchState(CountyAssignment("dispatch-103", route.destinationCounty, 1, 1),
            initial.state.checkpoint.path, initial.state.checkpoint.cursor, Phase(200, 1, 1),
            LandMarchStop.BUDGET_EXHAUSTED)
        val withMarch = before.copy(meta = before.meta + (MarchState.META_KEY to march.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(withMarch))
        world.applyGeneralDirtyFree(withMarch)
        fixture.nextPhase(world)

        assertIs<TravelExecution.Applied>(executor.resume(actor.id,
            fixture.metrics.edgesById.getValue(initial.state.checkpoint.path.edgeIds.first()).costMm) { LandMarchEntry.CLEAR })
        assertNull(MarchState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertNotNull(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection())
    }
}
