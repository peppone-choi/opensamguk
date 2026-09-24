package opensamguk.engine.hwiha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.*
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.LandMarchStop

class HwihaTravelHandlerTest {
    @Test
    fun `move starts a saved route and the next empty phase resumes it`() {
        val fixture = HwihaCampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(201, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val handler = HwihaTravelHandler(world, recorder, fixture.topology, fixture.metrics)
        val raw = HwihaTravelInput.canonicalJson(HwihaTravelRequest(actor.id, HwihaTravelInput.MOVE, route.destination))
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaTravelInput.MOVE, actor.id, raw, "move-201", 42))
        val first = assertNotNull(HwihaTravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals("move-201", first.orderId)
        assertEquals(route.destination, first.destination)
        assertTrue(first.checkpoint.path.totalCostMm > LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        assertTrue(world.positionOf(actor.id) != route.destination)
        fixture.nextPhase(world)
        assertEquals(true, HwihaTravelTurn(world, recorder, fixture.topology, fixture.metrics,
            HwihaMarchReactionPolicy.NON_BLOCKING).onTurn(actor.id))
        val second = assertNotNull(HwihaTravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals("move-201", second.orderId)
        assertEquals(route.destination, second.destination)
        assertEquals(false, second.checkpoint.lastAdvancedAt == first.checkpoint.lastAdvancedAt)
    }

    @Test
    fun `return resolves the dispatched county and missing assignment is rejected`() {
        val fixture = HwihaCampaignWorldFixture()
        val route = fixture.route()
        val assignment = HwihaCountyAssignment("dispatch-202", 99, 1, route.startCity)
        val base = fixture.person(202, 1, fixture.cityIn(route.destination), userId = "42")
        val actor = base.copy(meta = base.meta + (HwihaCountyAssignment.META_KEY to assignment.toMetaValue()))
        val world = fixture.world(listOf(actor to route.destination))
        val handler = HwihaTravelHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaTravelInput.RETURN, actor.id, "{}", "return-202", 42))
        val saved = assertNotNull(HwihaTravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals(route.start, saved.destination)
        assertEquals("dispatch-202", saved.assignmentIdAtStart)

        val unassigned = fixture.person(203, 1, fixture.cityIn(route.destination), userId = "42")
        val other = fixture.world(listOf(unassigned to route.destination))
        val rejected = assertIs<HwihaTurnOutcome.Rejected>(HwihaTravelHandler(other, ChangeRecorder(), fixture.topology,
            fixture.metrics).handle(HwihaTravelInput.RETURN, unassigned.id, "{}", "return-203", 42))
        assertEquals(HwihaTravelFailure.NO_RETURN_ASSIGNMENT.name, rejected.code)
    }

    @Test
    fun `lone traveler stops before a hostile reaction without entering combat`() {
        val fixture = HwihaCampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(204, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val handler = HwihaTravelHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics,
            HwihaMarchReactionPolicy { _, _, _ -> LandMarchEntry.ENCOUNTER })
        val raw = HwihaTravelInput.canonicalJson(HwihaTravelRequest(actor.id, HwihaTravelInput.MOVE, route.destination))
        assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaTravelInput.MOVE, actor.id, raw, "move-204", 42))
        val saved = assertNotNull(HwihaTravelState.read(world.getGeneralById(actor.id)!!.meta,
            fixture.topology, fixture.metrics))
        assertEquals(LandMarchStop.ENCOUNTER_UNAVAILABLE, saved.checkpoint.stop)
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(0L, saved.checkpoint.cursor.paidMm)
    }
}
