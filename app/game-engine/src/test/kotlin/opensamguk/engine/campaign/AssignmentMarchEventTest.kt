package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.CountyAssignment
import opensamguk.logic.input.DispatchAssessment
import opensamguk.logic.input.DispatchFailure
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole

class AssignmentMarchEventTest {
    @Test
    fun `NPC assignment march stops when the issuing lord becomes human owned`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val issuer = fixture.person(1, 1, route.startCity, userId = "42")
        val assignment = CountyAssignment("npc-dispatch", issuer.id, 1, route.destinationCounty)
        val target = fixture.person(301, 1, route.startCity, lord = false).copy(
            meta = fixture.person(301, 1, route.startCity, lord = false).meta +
                (CountyAssignment.META_KEY to assignment.toMetaValue()))
        val world = fixture.world(listOf(issuer to route.start, target to route.start),
            retainers = listOf(Retainer(1, issuer.id, "EXISTING", target.id, target.name, "guest")),
            cityChanges = { city -> city.copy(nationId = 1) })
        val recorder = ChangeRecorder()
        assertEquals(DispatchFailure.NPC_ISSUER_REQUIRED,
            assertIs<DispatchAssessment.Rejected>(
                DispatchExecutor(world, recorder).assessAssignment(target.id, assignment)).reason)
        val position = world.generalPositionSnapshot()!!.stateFor(target.id)

        fixture.movement(world, recorder).onTurn(target.id, CampaignWorldFixture.NO_INPUT)

        assertEquals(position, world.generalPositionSnapshot()!!.stateFor(target.id))
        assertEquals(assignment, CountyAssignment.read(world.getGeneralById(target.id)!!.meta))
        assertTrue(world.peekLogs().any { it.generalId == target.id &&
            it.text == "발령이 더 이상 유효하지 않아 부임 행군을 멈췄습니다." })
    }

    @Test
    fun `human and NPC assignment movement emit one typed self event in the world flush`() {
        for (userId in listOf("42", null)) {
            val fixture = CampaignWorldFixture()
            val route = fixture.route()
            val base = fixture.person(301, 1, route.startCity, userId = userId, lord = false)
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
}
