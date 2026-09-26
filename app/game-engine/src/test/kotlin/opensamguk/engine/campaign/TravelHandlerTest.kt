package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.LandMarchStop
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.GeneralPositionChangeResult
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.engine.turn.GeneralStats
import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole

class TravelHandlerTest {
    @Test
    fun `move starts a saved route and the next empty phase resumes it`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(201, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val handler = TravelHandler(world, recorder, fixture.topology, fixture.metrics)
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.MOVE, actor.id, raw, "move-201", 42))
        val first = assertNotNull(TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals("move-201", first.orderId)
        assertEquals(route.destination, first.destination)
        assertTrue(first.checkpoint.path.totalCostMm > LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        assertTrue(world.positionOf(actor.id) != route.destination)
        fixture.nextPhase(world)
        assertEquals(true, TravelTurn(world, recorder, fixture.topology, fixture.metrics,
            MarchReactionPolicy.NON_BLOCKING).onTurn(actor.id))
        val second = assertNotNull(TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals("move-201", second.orderId)
        assertEquals(route.destination, second.destination)
        assertEquals(false, second.checkpoint.lastAdvancedAt == first.checkpoint.lastAdvancedAt)
        val events = world.consumeDirtyState().gameEvents.filter { it.kind == EventKind.MARCH_DIRECT }
        assertEquals(2, events.size)
        assertEquals(2, events.map { it.eventKey }.toSet().size)
        assertTrue(events.all { it.audience == AudienceTarget.Self(actor.id) &&
            it.refs[RefRole.ACTOR] == EventRef.General(actor.id) })
    }

    @Test
    fun `opaque order id yields one typed direct march on retry`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(212, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val handler = TravelHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        val orderId = "." + "한".repeat(127)
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.MOVE, actor.id, raw, orderId, 42))
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.MOVE, actor.id, raw, orderId, 42))
        assertEquals(1, world.consumeDirtyState().gameEvents.count { it.kind == EventKind.MARCH_DIRECT })
    }

    @Test
    fun `return resolves the dispatched county and missing assignment is rejected`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val assignment = CountyAssignment("dispatch-202", 99, 1, route.startCity)
        val base = fixture.person(202, 1, fixture.cityIn(route.destination), userId = "42")
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to assignment.toMetaValue()))
        val world = fixture.world(listOf(actor to route.destination))
        val handler = TravelHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.RETURN, actor.id, "{}", "return-202", 42))
        val saved = assertNotNull(TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals(route.start, saved.destination)
        assertEquals("dispatch-202", saved.assignmentIdAtStart)

        val unassigned = fixture.person(203, 1, fixture.cityIn(route.destination), userId = "42")
        val other = fixture.world(listOf(unassigned to route.destination))
        val rejected = assertIs<TurnOutcome.Rejected>(TravelHandler(other, ChangeRecorder(), fixture.topology,
            fixture.metrics).handle(TravelInput.RETURN, unassigned.id, "{}", "return-203", 42))
        assertEquals(TravelFailure.NO_RETURN_ASSIGNMENT.name, rejected.code)
    }

    @Test
    fun `lone traveler fights hostile commander without creating troops`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(204, 1, route.startCity, userId = "42",
            stats = GeneralStats(100, 100, 70, 70, 70))
        val enemy = fixture.person(205, 2, route.startCity, stats = GeneralStats(10, 10, 70, 70, 70))
        val world = fixture.world(listOf(actor to route.start, enemy to route.first),
            bugoks = listOf(fixture.unit(1205, enemy.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, enemy.id, listOf(1205), route.destination)
        val handler = TravelHandler(world, recorder, fixture.topology, fixture.metrics)
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.MOVE, actor.id, raw, "move-204", 42))
        val saved = assertNotNull(TravelState.read(world.getGeneralById(actor.id)!!.meta,
            fixture.topology, fixture.metrics))
        assertTrue(saved.checkpoint.stop != LandMarchStop.ENCOUNTER)
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(100, world.getBugokById(1205)!!.troops)
        assertEquals("WON", (world.getGeneralById(actor.id)!!.meta[PersonalEncounter.REPLAY_KEY] as Map<*, *>)["outcome"])
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.MOVE, actor.id, raw, "move-204", 42))
        assertEquals(100, world.getBugokById(1205)!!.troops)
    }

    @Test
    fun `defeated lone traveler retreats and ends the direct route`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(206, 1, route.startCity, userId = "42",
            stats = GeneralStats(10, 10, 70, 70, 70))
        val enemy = fixture.person(207, 2, route.startCity, stats = GeneralStats(100, 100, 70, 70, 70))
        val world = fixture.world(listOf(actor to route.start, enemy to route.first),
            bugoks = listOf(fixture.unit(1207, enemy.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, enemy.id, listOf(1207), route.destination)
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        assertIs<TurnOutcome.Applied>(TravelHandler(world, recorder, fixture.topology,
            fixture.metrics).handle(TravelInput.MOVE, actor.id, raw, "move-206", 42))
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(null, TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertTrue(world.getGeneralById(actor.id)!!.injury > 0)
    }

    @Test
    fun `captured lone traveler remains with the captor for later persuasion`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(210, 1, route.startCity, userId = "42",
            stats = GeneralStats(10, 10, 70, 70, 70))
        val actor = base.copy(meta = base.meta + (PersonalTravelCondition.META_KEY to
            PersonalTravelCondition(0, 20).toMetaValue()))
        val enemy = fixture.person(211, 2, route.startCity, stats = GeneralStats(100, 100, 70, 70, 70))
        val world = fixture.world(listOf(actor to route.start, enemy to route.first),
            bugoks = listOf(fixture.unit(1211, enemy.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, enemy.id, listOf(1211), route.destination)
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        assertIs<TurnOutcome.Applied>(TravelHandler(world, recorder, fixture.topology,
            fixture.metrics).handle(TravelInput.MOVE, actor.id, raw, "move-210", 42))
        assertEquals("CAPTURED", (world.getGeneralById(actor.id)!!.meta[PersonalEncounter.REPLAY_KEY] as Map<*, *>)["outcome"])
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(enemy.id, (world.getGeneralById(actor.id)!!.meta[EncounterResolver.CAPTIVE_KEY] as Map<*, *>)["captorGeneralId"])
    }

    @Test
    fun `interceptor entering the province becomes the personal encounter defender`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(208, 1, route.startCity, userId = "42",
            stats = GeneralStats(100, 100, 70, 70, 70))
        val enemy = fixture.person(209, 2, route.destinationCounty, stats = GeneralStats(10, 10, 70, 70, 70))
        val world = fixture.world(listOf(actor to route.start, enemy to route.destination),
            bugoks = listOf(fixture.unit(1209, enemy.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, enemy.id, listOf(1209), route.first)
        val reaction = object : MarchReactionPolicy {
            override fun entryHazard(world: InMemoryTurnWorld, actorId: Int,
                node: StrategicNodeRef.LandProvince): LandMarchEntry =
                if (node == route.first) LandMarchEntry.ENCOUNTER else LandMarchEntry.CLEAR
            override fun interceptsAt(world: InMemoryTurnWorld, actorId: Int,
                node: StrategicNodeRef.LandProvince) = node == route.first
            override fun directEntryHazard(world: InMemoryTurnWorld, actorId: Int,
                node: StrategicNodeRef.LandProvince) = entryHazard(world, actorId, node)
            override fun directInterceptsAt(world: InMemoryTurnWorld, actorId: Int,
                node: StrategicNodeRef.LandProvince) = interceptsAt(world, actorId, node)
            override fun onEntered(world: InMemoryTurnWorld, recorder: ChangeRecorder, actorId: Int,
                node: StrategicNodeRef.LandProvince) {
                if (node == route.first)
                    assertIs<GeneralPositionChangeResult.Changed>(recorder.moveGeneral(world, enemy.id, node))
            }
            override fun onDirectEntered(world: InMemoryTurnWorld, recorder: ChangeRecorder, actorId: Int,
                node: StrategicNodeRef.LandProvince) = onEntered(world, recorder, actorId, node)
        }
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        assertIs<TurnOutcome.Applied>(TravelHandler(world, recorder, fixture.topology,
            fixture.metrics, reaction).handle(TravelInput.MOVE, actor.id, raw, "move-208", 42))
        assertEquals(route.first, world.positionOf(enemy.id))
        assertEquals("WON", (world.getGeneralById(actor.id)!!.meta[PersonalEncounter.REPLAY_KEY] as Map<*, *>)["outcome"])
    }
}
