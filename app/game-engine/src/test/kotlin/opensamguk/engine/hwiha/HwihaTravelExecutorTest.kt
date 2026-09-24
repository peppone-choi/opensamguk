package opensamguk.engine.hwiha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.HwihaTravelInput
import opensamguk.logic.input.HwihaTravelRequest
import opensamguk.logic.input.HwihaTravelState
import opensamguk.logic.input.HwihaPersonalTravelCondition
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.LandMarchStop

class HwihaTravelExecutorTest {
    @Test
    fun `direct travel pays each edge over successive phases and replays once`() {
        val fixture = HwihaCampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(101, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val executor = HwihaTravelExecutor(world, recorder, fixture.topology, fixture.metrics)
        val request = HwihaTravelRequest(actor.id, HwihaTravelInput.MOVE, route.destination)
        val partial = assertIs<HwihaTravelExecution.Applied>(
            executor.start("travel-101", request, route.destination, 1) { LandMarchEntry.CLEAR })
        val firstCost = fixture.metrics.edgesById.getValue(partial.state.checkpoint.path.edgeIds.first()).costMm
        assertEquals(LandMarchStop.BUDGET_EXHAUSTED, partial.state.checkpoint.stop)
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(0, partial.movement.reachedNodes.size)
        assertIs<HwihaTravelExecution.AlreadyProcessed>(
            executor.start("travel-101", request, route.destination, 1) { LandMarchEntry.CLEAR })
        assertIs<HwihaTravelExecution.AlreadyProcessed>(
            executor.start("travel-102", request, route.destination, 1) { LandMarchEntry.CLEAR })

        fixture.nextPhase(world)
        val reached = assertIs<HwihaTravelExecution.Applied>(
            executor.resume(actor.id, firstCost - 1) { LandMarchEntry.CLEAR })
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(listOf(route.first), reached.movement.reachedNodes)
        assertIs<HwihaTravelExecution.AlreadyProcessed>(
            executor.resume(actor.id, firstCost) { LandMarchEntry.CLEAR })
        assertEquals("travel-101", HwihaTravelState.read(world.getGeneralById(actor.id)!!.meta,
            fixture.topology, fixture.metrics)?.orderId)
    }

    @Test
    fun `forced march persists personal cost once on the recorder path`() {
        val fixture = HwihaCampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(102, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val executor = HwihaTravelExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val request = HwihaTravelRequest(actor.id, HwihaTravelInput.FORCED_MARCH, route.destination)
        val first = assertIs<HwihaTravelExecution.Applied>(
            executor.start("forced-102", request, route.destination, 45_000_000) { LandMarchEntry.CLEAR })
        val condition = HwihaPersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta)
        assertEquals(first.condition, condition)
        assertIs<HwihaTravelExecution.AlreadyProcessed>(
            executor.start("forced-102", request, route.destination, 45_000_000) { LandMarchEntry.CLEAR })
        assertEquals(condition, HwihaPersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
    }
}
