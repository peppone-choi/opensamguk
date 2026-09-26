package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole

class CorpsMarchEventTest {
    @Test
    fun `successful corps advance records one private event per phase`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val commander = fixture.person(313, 1, route.startCity)
        val world = fixture.world(listOf(commander to route.start),
            bugoks = listOf(fixture.unit(1313, commander.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, commander.id, listOf(1313), route.destination,
            orderId = "." + "한".repeat(127))
        fixture.nextPhase(world)
        val march = CorpsMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells)
        assertTrue(march.onTurn(commander.id))
        assertTrue(march.onTurn(commander.id))
        fixture.nextPhase(world)
        assertTrue(march.onTurn(commander.id))
        val events = world.consumeDirtyState().gameEvents.filter { it.kind == EventKind.MARCH_CORPS }
        assertEquals(2, events.size)
        assertEquals(2, events.map { it.eventKey }.toSet().size)
        assertTrue(events.all { it.audience == AudienceTarget.Self(commander.id) &&
            it.refs[RefRole.ACTOR] == EventRef.General(commander.id) &&
            it.refs[RefRole.CITY] == EventRef.City(route.destinationCounty) })
    }
}
