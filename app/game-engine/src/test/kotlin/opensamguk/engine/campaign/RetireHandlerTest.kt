package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.InputRejection
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.RetireFailure
import opensamguk.engine.turn.Nation

class RetireHandlerTest {
    private val fixture = CampaignWorldFixture()

    @Test fun `retirement stays unavailable until succession state is complete`() {
        val route = fixture.route()
        val actor = fixture.person(941, 1, route.startCity, userId = "42", lord = true).copy(age = 60, gold = 30, rice = 40)
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

    private fun deliveredCatalog(): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.retire"[\s\S]*?"deliveryState":\s*")PLANNED(")""")
        assertTrue(row.containsMatchIn(original))
        return InputCatalog.parse(row.replace(original, "${'$'}1HANDLER_READY${'$'}2"))
    }

    @Test fun `age successor and owner rejections leave all succession state untouched`() {
        val route = fixture.route()
        val actor = fixture.person(961, 1, route.startCity, userId = "42").copy(age = 60, gold = 30, rice = 40)
        val heir = fixture.person(962, 1, route.startCity, lord = false).copy(age = 30, gold = 5, rice = 6)
        val cases = listOf(
            Triple(actor.copy(age = 59), heir, RetireFailure.AGE_TOO_YOUNG.name),
            Triple(actor.copy(age = 0), heir, RetireFailure.AGE_TOO_YOUNG.name),
            Triple(actor.copy(userId = "43"), heir, "FORBIDDEN"),
            Triple(actor, heir.copy(userId = "44"), RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL.name),
            Triple(actor, heir.copy(nationId = 2), RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL.name),
            Triple(actor, heir.copy(meta = heir.meta + ("retired" to true)), RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL.name),
        ) + listOf(-1, 0, 1, 3, 5, 6, 9, 99).map { npc ->
            Triple(actor, heir.copy(npcState = npc), RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL.name)
        }
        for ((subject, successor, code) in cases) {
            val world = fixture.world(listOf(subject to route.start, successor to route.start),
                bugoks = listOf(fixture.unit(21, actor.id, 100)),
                retainers = listOf(Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "guest")))
            val generals = world.listGenerals()
            val retainers = world.listRetainers()
            val bugoks = world.listBugoks()
            val nations = world.listNations()
            val positions = world.generalPositionSnapshot()
            val recorder = ChangeRecorder()
            val result = assertIs<TurnOutcome.Rejected>(RetireHandler(world, recorder, DomesticContext(),
                deliveredCatalog()).handle(actor.id, """{"successorGeneralId":962}""", "retire-961", 42))
            assertEquals(code, result.code, "subject=$subject successor=$successor")
            assertEquals(generals, world.listGenerals())
            assertEquals(retainers, world.listRetainers())
            assertEquals(bugoks, world.listBugoks())
            assertEquals(nations, world.listNations())
            assertEquals(positions, world.generalPositionSnapshot())
            assertFalse(recorder.isDirty)
        }
    }

    @Test fun `bound ruler mismatch rejects before writing successor assets or control`() {
        val route = fixture.route()
        val actor = fixture.person(971, 1, route.startCity, userId = "42").copy(age = 60)
        val heir = fixture.person(972, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start),
            nations = listOf(Nation(1, "국", "#111111", chiefGeneralId = heir.id)),
            retainers = listOf(Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "guest")))
        val recorder = ChangeRecorder()
        val result = assertIs<TurnOutcome.Rejected>(RetireHandler(world, recorder, DomesticContext(),
            deliveredCatalog()).handle(actor.id, """{"successorGeneralId":972}""", "retire-971", 42))
        assertEquals(RetireFailure.STATE_UNAVAILABLE.name, result.code)
        assertEquals(actor, world.getGeneralById(actor.id))
        assertEquals(heir, world.getGeneralById(heir.id))
        assertEquals(heir.id, world.getNationById(1)!!.chiefGeneralId)
        assertEquals(1, world.listRetainers().size)
        assertFalse(recorder.isDirty)
    }

}
