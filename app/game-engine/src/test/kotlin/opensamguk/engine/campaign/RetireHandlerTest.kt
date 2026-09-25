package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.InputRejection

class RetireHandlerTest {
    private val fixture = CampaignWorldFixture()

    @Test fun `retirement stays unavailable until succession state is complete`() {
        val route = fixture.route()
        val actor = fixture.person(941, 1, route.startCity, userId = "42", lord = true).copy(gold = 30, rice = 40)
        val heir = fixture.person(942, 1, route.startCity, lord = false).copy(gold = 5, rice = 6)
        val follower = fixture.person(943, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start, follower to route.start))
        world.createRetainer(Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "guest"))
        world.createRetainer(Retainer(12, actor.id, "EXISTING", follower.id, follower.name, "guest"))
        val recorder = ChangeRecorder()
        val handler = RetireHandler(world, recorder, DomesticContext())
        val args = """{"successorGeneralId":942}"""
        val rejected = assertIs<TurnOutcome.Rejected>(handler.handle(actor.id, args, "retire-941", 42))
        assertEquals(InputRejection.NOT_DELIVERED.name, rejected.code)
        assertEquals(actor, world.getGeneralById(actor.id))
        assertEquals(heir, world.getGeneralById(heir.id))
        assertEquals(listOf(11, 12), world.listRetainers().map { it.id })
        assertFalse(recorder.isDirty)
    }

    @Test fun `missing direct card rejects without changing general ownership`() {
        val route = fixture.route()
        val actor = fixture.person(951, 1, route.startCity, userId = "42", lord = true)
        val heir = fixture.person(952, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start))
        val result = assertIs<TurnOutcome.Rejected>(RetireHandler(world, ChangeRecorder(),
            DomesticContext()).handle(actor.id, """{"successorGeneralId":952}""", "retire-951", 42))
        assertEquals(InputRejection.NOT_DELIVERED.name, result.code)
        assertEquals(actor, world.getGeneralById(actor.id))
    }

    @Test fun `old NPC does not select undelivered retirement`() {
        val route = fixture.route()
        val actor = fixture.person(961, 1, route.startCity, lord = true).copy(age = 80)
        val heir = fixture.person(962, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start),
            retainers = listOf(Retainer(13, actor.id, "EXISTING", heir.id, heir.name, "guest")))
        val chosen = NpcRetireSelector(DomesticContext()).select(world, actor.id,
            CampaignWorldFixture.NO_INPUT)
        assertEquals(CampaignWorldFixture.NO_INPUT, chosen)
    }
}
