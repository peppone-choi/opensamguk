package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.HwihaLordStatus
import opensamguk.logic.input.HwihaRetireFailure

class HwihaRetireHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()

    @Test fun `named successor receives control and retinue exactly once`() {
        val route = fixture.route()
        val actor = fixture.person(941, 1, route.startCity, userId = "42", lord = true).copy(gold = 30, rice = 40)
        val heir = fixture.person(942, 1, route.startCity, lord = false).copy(gold = 5, rice = 6)
        val follower = fixture.person(943, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start, follower to route.start))
        world.createRetainer(Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "guest"))
        world.createRetainer(Retainer(12, actor.id, "EXISTING", follower.id, follower.name, "guest"))
        val handler = HwihaRetireHandler(world, ChangeRecorder(), HwihaDomesticContext())
        val args = """{"successorGeneralId":942}"""
        val applied = assertIs<HwihaTurnOutcome.Applied>(handler.handle(actor.id, args, "retire-941", 42))
        assertEquals(applied, handler.handle(actor.id, args, "retire-941", 42))
        val retired = world.getGeneralById(actor.id)!!
        val successor = world.getGeneralById(heir.id)!!
        assertEquals(true, retired.meta["hwihaRetired"])
        assertNull(retired.userId)
        assertEquals("42", successor.userId)
        assertEquals(35, successor.gold)
        assertEquals(46, successor.rice)
        assertTrue(HwihaLordStatus.read(successor.meta))
        assertFalse(HwihaLordStatus.read(retired.meta))
        assertEquals(listOf(12), world.listRetainers().map { it.id })
        assertEquals(heir.id, world.listRetainers().single().masterGeneralId)
        assertEquals(heir.id, world.getNationById(actor.nationId)!!.chiefGeneralId)
    }

    @Test fun `missing direct card rejects without changing general ownership`() {
        val route = fixture.route()
        val actor = fixture.person(951, 1, route.startCity, userId = "42", lord = true)
        val heir = fixture.person(952, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start))
        val result = assertIs<HwihaTurnOutcome.Rejected>(HwihaRetireHandler(world, ChangeRecorder(),
            HwihaDomesticContext()).handle(actor.id, """{"successorGeneralId":952}""", "retire-951", 42))
        assertEquals(HwihaRetireFailure.SUCCESSOR_NOT_RETAINER.name, result.code)
        assertEquals(actor, world.getGeneralById(actor.id))
    }

    @Test fun `old NPC selects an eligible direct successor from visible cards`() {
        val route = fixture.route()
        val actor = fixture.person(961, 1, route.startCity, lord = true).copy(age = 80)
        val heir = fixture.person(962, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start),
            retainers = listOf(Retainer(13, actor.id, "EXISTING", heir.id, heir.name, "guest")))
        val chosen = HwihaNpcRetireSelector(HwihaDomesticContext()).select(world, actor.id,
            HwihaCampaignWorldFixture.NO_INPUT)
        assertEquals("action.retire", chosen.actionCode)
        assertEquals("""{"successorGeneralId":962}""", chosen.argJson)
    }
}
