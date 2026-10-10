package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.InputRejection
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.RetireFailure
import opensamguk.logic.input.PersonPolicyState
import opensamguk.logic.input.RetireRules
import opensamguk.logic.input.RetireRequest
import opensamguk.logic.input.RetireAssessment
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

    private fun withCapacity(person: opensamguk.engine.turn.TurnGeneral, capacity: Int) = person.copy(
        meta = person.meta + (PersonPolicyState.META_KEY to
            PersonPolicyState.read(person.meta)!!.copy(renownCapacity = capacity).toMetaValue()))

    @Test fun `execution rechecks changed capacity and repeated excess rejection has no succession side effects`() {
        val route = fixture.route()
        val actor = fixture.person(991, 1, route.startCity, userId = "42").copy(age = 60, gold = 300, rice = 400)
        val heir = withCapacity(fixture.person(992, 1, route.startCity, lord = false), 7)
        val follower = fixture.person(993, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start, follower to route.start),
            nations = listOf(Nation(1, "국", "#111111", chiefGeneralId = actor.id)),
            bugoks = listOf(fixture.unit(21, actor.id, 100)), retainers = listOf(
                Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "lieutenant"),
                Retainer(12, actor.id, "EXISTING", follower.id, follower.name, "staff")))
        assertIs<RetireAssessment.Eligible>(RetireRules.assess(RetireRequest(actor.id, heir.id),
            DomesticContext().projection(world)))
        // Simulate a changed authoritative cap after admission, before execution.
        world.applyGeneralDirtyFree(withCapacity(heir, 6))
        val generals = world.listGenerals()
        val retainers = world.listRetainers()
        val bugoks = world.listBugoks()
        val nations = world.listNations()
        val positions = world.generalPositionSnapshot()
        val recorder = ChangeRecorder()
        val handler = RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
        for (requestId in listOf("retire-991", "retire-991", "retire-991-new")) {
            val result = assertIs<TurnOutcome.Rejected>(handler.handle(actor.id,
                """{"successorGeneralId":992}""", requestId, 42))
            assertEquals(RetireFailure.SUCCESSOR_RENOWN_EXCEEDED.name, result.code)
            assertEquals(generals, world.listGenerals())
            assertEquals(retainers, world.listRetainers())
            assertEquals(bugoks, world.listBugoks())
            assertEquals(nations, world.listNations())
            assertEquals(positions, world.generalPositionSnapshot())
            assertFalse(recorder.isDirty)
        }
        // Ownership and heir validity remain prior gates even when the resulting cost exceeds the cap.
        assertEquals("FORBIDDEN", assertIs<TurnOutcome.Rejected>(handler.handle(actor.id,
            """{"successorGeneralId":992}""", "wrong-owner", 43)).code)
        world.applyGeneralDirtyFree(withCapacity(heir, 0).copy(userId = "44"))
        assertEquals(RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(actor.id,
                """{"successorGeneralId":992}""", "wrong-heir", 42)).code)
        assertFalse(recorder.isDirty)
    }

    @Test fun `existing succession skeleton keeps exact successor policy at capacity and replays without a second transfer`() {
        val route = fixture.route()
        val actor = withCapacity(fixture.person(994, 1, route.startCity, userId = "42"), 999)
            .copy(age = 60, gold = 30, rice = 40)
        val heir = withCapacity(fixture.person(995, 1, route.startCity, lord = false), 14)
            .copy(gold = 5, rice = 6)
        val follower = fixture.person(996, 1, route.startCity, lord = false)
        val existing = fixture.person(997, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor, heir, follower, existing).map { it to route.start },
            nations = listOf(Nation(1, "국", "#111111", chiefGeneralId = actor.id)), retainers = listOf(
                Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "lieutenant"),
                Retainer(12, actor.id, "EXISTING", follower.id, follower.name, "staff"),
                Retainer(13, heir.id, "EXISTING", existing.id, existing.name, "guest")))
        val recorder = ChangeRecorder()
        val handler = RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
        val first = assertIs<TurnOutcome.Applied>(handler.handle(actor.id,
            """{"successorGeneralId":995}""", "retire-994", 42))
        assertEquals(setOf(actor.id), recorder.generalOwnerDeletes())
        val successor = world.getGeneralById(heir.id)!!
        assertEquals(heir.meta[PersonPolicyState.META_KEY], successor.meta[PersonPolicyState.META_KEY])
        assertEquals(14, PersonPolicyState.read(successor.meta)!!.renownCapacity)
        assertEquals(35, successor.gold)
        assertEquals(46, successor.rice)
        assertEquals(listOf(12, 13), world.listRetainers().map { it.id })
        assertTrue(world.listRetainers().all { it.masterGeneralId == heir.id })
        val snapshot = world.listGenerals() to world.listRetainers()
        val replayRecorder = ChangeRecorder()
        val replay = RetireHandler(world, replayRecorder, DomesticContext(), deliveredCatalog())
        assertEquals(first, replay.handle(actor.id, """{"successorGeneralId":995}""", "retire-994", 42))
        assertEquals(snapshot, world.listGenerals() to world.listRetainers())
        assertFalse(replayRecorder.isDirty)
        val retired = world.getGeneralById(actor.id)!!
        world.applyGeneralDirtyFree(retired.copy(turnTime = retired.turnTime.plusSeconds(3600)))
        world.consumeDirtyState()
        val empty = world.consumeDirtyState()
        val advancedSnapshot = world.listGenerals() to world.listRetainers()
        // A new handler with the production PLANNED catalog can replay a committed result.
        val advancedReplay = RetireHandler(world, replayRecorder, DomesticContext())
        assertEquals(first, advancedReplay.handle(actor.id, """{"successorGeneralId":995}""", "retire-994", 42))
        assertEquals(advancedSnapshot, world.listGenerals() to world.listRetainers())
        assertFalse(replayRecorder.isDirty)
        assertEquals(empty, world.consumeDirtyState())
    }

    @Test fun `unowned NPC retirement does not delete a player ownership row`() {
        val route = fixture.route()
        val actor = fixture.person(1001, 1, route.startCity, lord = false).copy(age = 60)
        val heir = fixture.person(1002, 1, route.startCity, lord = false)
        val world = fixture.world(listOf(actor to route.start, heir to route.start),
            retainers = listOf(Retainer(11, actor.id, "EXISTING", heir.id, heir.name, "guest")))
        val recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(RetireHandler(world, recorder, DomesticContext(), deliveredCatalog())
            .handle(actor.id, """{"successorGeneralId":1002}""", null, null, npcSelected = true))
        assertTrue(recorder.generalOwnerDeletes().isEmpty())
    }

    @Test fun `retired replay rejects different identities missing keys and damaged stamps without writes`() {
        val route = fixture.route()
        val actor = fixture.person(998, 1, route.startCity).copy(npcState = 5, meta = mapOf("retired" to true))
        val stamp = mapOf("turn" to actor.turnTime.toString(), "requestId" to "retire-998", "ownerUserId" to 42,
            "successorGeneralId" to 999, "effects" to listOf("successorGeneralId:999", "retainers:0", "bugoks:0"))
        val cases = listOf(
            Triple(stamp, "another-request", 42), Triple(stamp, null, 42),
            Triple(stamp, "retire-998", 43), Triple(stamp, "retire-998", null),
            Triple(stamp + ("requestId" to null), null, 42),
            Triple(stamp + ("ownerUserId" to null), "retire-998", null),
        )
        for ((saved, requestId, owner) in cases) {
            val world = fixture.world(listOf(actor.copy(meta = actor.meta + ("retireLastTurn" to saved)) to route.start))
            val recorder = ChangeRecorder()
            world.consumeDirtyState() // Fixture construction initializes the city military ledger.
            val empty = world.consumeDirtyState()
            val before = world.listGenerals()
            val handler = RetireHandler(world, recorder, DomesticContext())
            for (heir in listOf(999, 1000)) {
                assertEquals(RetireFailure.ALREADY_RETIRED.name, assertIs<TurnOutcome.Rejected>(
                    handler.handle(actor.id, """{"successorGeneralId":$heir}""", requestId, owner)).code)
                assertEquals(before, world.listGenerals())
                assertFalse(recorder.isDirty)
                assertEquals(empty, world.consumeDirtyState())
            }
        }
        val damaged = listOf(null, "broken", stamp - "turn", stamp + ("turn" to "not-an-instant"),
            stamp - "requestId", stamp + ("requestId" to 1), stamp - "ownerUserId",
            stamp + ("ownerUserId" to "42"), stamp + ("ownerUserId" to 0),
            stamp + ("successorGeneralId" to 0), stamp + ("successorGeneralId" to actor.id),
            stamp + ("effects" to listOf("successorGeneralId:999", "retainers:0", 1)),
            stamp + ("effects" to listOf("successorGeneralId:999", "bugoks:0", "retainers:0")),
            stamp + ("effects" to listOf("successorGeneralId:1000", "retainers:0", "bugoks:0")),
            stamp + ("effects" to listOf("successorGeneralId:999", "retainers:-1", "bugoks:0")))
        for (saved in damaged) {
            val world = fixture.world(listOf(actor.copy(meta = actor.meta + ("retireLastTurn" to saved)) to route.start))
            val recorder = ChangeRecorder()
            world.consumeDirtyState()
            val empty = world.consumeDirtyState()
            val before = world.listGenerals()
            assertEquals(RetireFailure.STATE_UNAVAILABLE.name, assertIs<TurnOutcome.Rejected>(
                RetireHandler(world, recorder, DomesticContext()).handle(actor.id,
                    """{"successorGeneralId":999}""", "retire-998", 42)).code)
            assertEquals(before, world.listGenerals())
            assertFalse(recorder.isDirty)
            assertEquals(empty, world.consumeDirtyState())
        }
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        world.consumeDirtyState()
        val empty = world.consumeDirtyState()
        assertEquals(RetireFailure.ALREADY_RETIRED.name, assertIs<TurnOutcome.Rejected>(
            RetireHandler(world, recorder, DomesticContext()).handle(actor.id,
                """{"successorGeneralId":999}""", "retire-998", 42)).code)
        assertEquals(listOf(actor), world.listGenerals())
        assertFalse(recorder.isDirty)
        assertEquals(empty, world.consumeDirtyState())
    }

}
