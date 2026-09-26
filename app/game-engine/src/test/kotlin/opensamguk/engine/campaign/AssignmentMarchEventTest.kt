package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.CountyAssignment
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole

class AssignmentMarchEventTest {
    @Test
    fun `applied assignment movement emits one typed self event in the world flush`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(301, 1, route.startCity, userId = "42", lord = false)
        val issuer = fixture.person(1, 1, route.startCity)
        val assignment = CountyAssignment("dispatch-301", issuer.id, 1, route.destinationCounty)
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to assignment.toMetaValue()))
        val world = fixture.world(listOf(issuer to route.start, actor to route.start),
            retainers = listOf(Retainer(1, issuer.id, "EXISTING", actor.id, actor.name, "guest")),
            cityChanges = { city -> city.copy(nationId = 1) })
        val recorder = ChangeRecorder()
        val movement = fixture.movement(world, recorder)
        val assessment = DispatchExecutor(world, recorder).assessAssignment(actor.id, assignment)
        assertTrue(assessment is opensamguk.logic.input.DispatchAssessment.Eligible, "$assessment")

        movement.onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.gameEvents.isNotEmpty(), "march logs: ${dirty.logs.map { it.text }}")
        val payload = DatabaseHooks.toFlushPayload(world, recorder, dirty)
        val event = assertIs<opensamguk.logic.record.GameEvent>(payload.gameEvents.single())
        assertEquals(EventKind.MARCH_ASSIGNMENT, event.kind)
        assertEquals(AudienceTarget.Self(actor.id), event.audience)
        assertEquals(EventRef.General(actor.id), event.refs[RefRole.ACTOR])
        assertEquals(EventRef.City(route.destinationCounty), event.refs[RefRole.CITY])
        assertEquals(0, event.occurredAt.ordinal)
        assertTrue(payload.logEntries.isNotEmpty(), "legacy log remains until the replacement UI is ready")

        movement.onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
        assertTrue(world.consumeDirtyState().gameEvents.isEmpty(), "the same turn does not emit a second event")
    }
}
