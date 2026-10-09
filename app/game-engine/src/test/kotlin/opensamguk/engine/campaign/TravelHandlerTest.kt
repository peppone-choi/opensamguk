package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*
import opensamguk.logic.travel.PersonalReturnStop
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
    @Test fun `old long move reservation is rejected at execution without movement`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(213, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val handler = TravelHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val oldArguments = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination))
        val rejected = assertIs<TurnOutcome.Rejected>(handler.handle(TravelInput.MOVE,
            actor.id, oldArguments, "old-reservation-213", 42))
        assertEquals(TravelFailure.NO_ROUTE.name, rejected.code)
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(null, TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals(0, world.consumeDirtyState().gameEvents.count { it.kind == EventKind.MARCH_DIRECT })
    }

    @Test
    fun `return reaches only the next neighbor and owns later idle movement without automatic assignment march`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(201, 1, route.startCity, userId = "42")
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to
            CountyAssignment("dispatch-201", 99, 1, route.destinationCounty).toMetaValue()) +
            (PersonalTravelCondition.META_KEY to PersonalTravelCondition(100, 0).toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val handler = TravelHandler(world, recorder, fixture.topology, fixture.metrics)
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.RETURN, actor.id, "{}", "return-201", 42))
        val first = assertNotNull(TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals("return-201", first.orderId)
        assertEquals(route.first, first.destination)
        assertEquals(1, first.checkpoint.path.edgeIds.size)
        assertEquals(LandMarchStop.ARRIVED, first.checkpoint.stop)
        assertEquals(route.first, world.positionOf(actor.id))
        for (phase in 1..2) {
            fixture.nextPhase(world)
            fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
            assertEquals(route.first, world.positionOf(actor.id))
            assertEquals(first.toMetaValue(), world.getGeneralById(actor.id)!!.meta[TravelState.META_KEY])
            assertEquals(PersonalTravelCondition(100 - phase * 10, phase * 5),
                PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
            fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
            assertEquals(PersonalTravelCondition(100 - phase * 10, phase * 5),
                PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        }
        val events = world.consumeDirtyState().gameEvents.filter { it.kind == EventKind.MARCH_DIRECT }
        assertEquals(1, events.size)
        assertTrue(events.all { it.audience == AudienceTarget.Self(actor.id) &&
            it.refs[RefRole.ACTOR] == EventRef.General(actor.id) })
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.RETURN, actor.id, "{}", "return-201-next", 42))
        assertEquals(route.destination, world.positionOf(actor.id))
        assertEquals(route.destination, TravelState.read(world.getGeneralById(actor.id)!!.meta,
            fixture.topology, fixture.metrics)!!.destination)
    }

    @Test fun `return rechecks assignment closures and ownership with no rejected writes`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(214, 1, route.startCity, userId = "42")
        val assigned = base.copy(meta = base.meta + (CountyAssignment.META_KEY to
            CountyAssignment("dispatch-214", 99, 1, route.destinationCounty).toMetaValue()))
        for (variant in listOf("none", "home", "closed", "owner", "nation")) {
            val actor = when (variant) {
                "none" -> base
                "home" -> assigned.copy(meta = base.meta + (CountyAssignment.META_KEY to
                    CountyAssignment("dispatch-214", 99, 1, route.startCity).toMetaValue()))
                "nation" -> assigned.copy(nationId = 2)
                else -> assigned
            }
            val world = fixture.world(listOf(actor to route.start))
            if (variant == "closed") world.setGameEnvValue(LandPassageState.META_KEY,
                LandPassageState.initialMetaValue(fixture.topology) + ("edges" to
                    fixture.topology.traversalEdges.associate { it.id to mapOf("active" to false,
                        "seasonOpen" to true, "blockaded" to false, "availableCapacity" to it.capacity) }))
            world.consumeDirtyState()
            val before = world.getGeneralById(actor.id)!!
            val rejected = assertIs<TurnOutcome.Rejected>(TravelHandler(world, ChangeRecorder(), fixture.topology,
                fixture.metrics).handle(TravelInput.RETURN, actor.id, "{}", "return-214-$variant",
                    if (variant == "owner") 43 else 42))
            assertEquals(when (variant) {
                "none", "nation" -> TravelFailure.NO_RETURN_ASSIGNMENT.name
                "home" -> TravelFailure.ALREADY_THERE.name
                "closed" -> TravelFailure.NO_ROUTE.name
                else -> "FORBIDDEN"
            }, rejected.code)
            assertEquals(before, world.getGeneralById(actor.id))
            assertEquals(route.start, world.positionOf(actor.id))
            val dirty = world.consumeDirtyState()
            assertEquals(0, dirty.generals.size)
            assertEquals(0, dirty.gameEvents.size)
            assertEquals(0, dirty.logs.size)
        }
    }

    @Test fun `return stop survives encounter checkpoint deletion and new valid movement alone clears it`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(215, 1, route.startCity, userId = "42",
            stats = GeneralStats(10, 10, 70, 70, 70))
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to
            CountyAssignment("dispatch-215", 99, 1, route.destinationCounty).toMetaValue()))
        val enemy = fixture.person(216, 2, route.startCity, stats = GeneralStats(100, 100, 70, 70, 70))
        val world = fixture.world(listOf(actor to route.start, enemy to route.first),
            bugoks = listOf(fixture.unit(1216, enemy.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, enemy.id, listOf(1216), route.destination)
        val handler = TravelHandler(world, recorder, fixture.topology, fixture.metrics)
        assertIs<TurnOutcome.Applied>(handler.handle(TravelInput.RETURN, actor.id, "{}", "return-215", 42))
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(null, TravelState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        val stopped = world.getGeneralById(actor.id)!!
        val marker = PersonalReturnStop("return-215", "dispatch-215")
        assertEquals(marker, PersonalReturnStop.read(stopped.meta))
        val oldCondition = PersonalTravelCondition.read(stopped.meta)!!
        val turn = TravelTurn(world, recorder, fixture.topology, fixture.metrics, MarchReactionPolicy.NON_BLOCKING)
        assertEquals(true, turn.onTurn(actor.id))
        assertEquals(oldCondition, PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(oldCondition.afterRest(), PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
        assertEquals(oldCondition.afterRest(), PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(marker, PersonalReturnStop.read(world.getGeneralById(actor.id)!!.meta))
        val beforeRejected = world.getGeneralById(actor.id)!!
        assertIs<TurnOutcome.Rejected>(handler.handle(TravelInput.MOVE, actor.id,
            TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.destination)), "bad-new-215", 42))
        assertEquals(beforeRejected, world.getGeneralById(actor.id))
        assertEquals(marker, PersonalReturnStop.read(world.getGeneralById(actor.id)!!.meta))
        assertIs<TravelExecution.Applied>(TravelExecutor(world, recorder, fixture.topology, fixture.metrics)
            .start("valid-new-215", TravelRequest(actor.id, TravelInput.MOVE, route.first), route.first, 1) { LandMarchEntry.CLEAR })
        assertEquals(null, PersonalReturnStop.read(world.getGeneralById(actor.id)!!.meta))
    }

    @Test fun `return stop respects assignment change captivity and malformed metadata without automatic movement`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(217, 1, route.startCity, userId = "42")
        val assignment = CountyAssignment("dispatch-217", 99, 1, route.destinationCounty)
        val marker = PersonalReturnStop("return-217", assignment.dispatchId)
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to assignment.toMetaValue()) +
            (PersonalReturnStop.META_KEY to marker.toMetaValue()) +
            (PersonalTravelCondition.META_KEY to PersonalTravelCondition(100, 0).toMetaValue()))
        for (bad in listOf(false, true)) {
            val held = actor.copy(meta = actor.meta + (if (bad) mapOf(PersonalReturnStop.META_KEY to
                mapOf("version" to 1)) else mapOf(CaptiveState.META_KEY to mapOf("version" to 1))))
            val world = fixture.world(listOf(held to route.start))
            val recorder = ChangeRecorder()
            world.consumeDirtyState()
            assertEquals(true, TravelTurn(world, recorder, fixture.topology, fixture.metrics,
                MarchReactionPolicy.NON_BLOCKING).onTurn(actor.id))
            assertEquals(held, world.getGeneralById(actor.id))
            assertEquals(route.start, world.positionOf(actor.id))
            assertEquals(0, world.consumeDirtyState().generals.size)
        }
        val world = fixture.world(listOf(actor.copy(meta = actor.meta + (CountyAssignment.META_KEY to
            assignment.copy(dispatchId = "new-dispatch-217").toMetaValue())) to route.start))
        val turn = TravelTurn(world, ChangeRecorder(), fixture.topology, fixture.metrics, MarchReactionPolicy.NON_BLOCKING)
        assertEquals(false, turn.onTurn(actor.id))
        assertEquals(null, PersonalReturnStop.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(route.start, world.positionOf(actor.id))
        val nationWorld = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(TravelHandler(nationWorld, recorder, fixture.topology, fixture.metrics)
            .handle(TravelInput.RETURN, actor.id, "{}", "nation-change-217", 42))
        val completed = nationWorld.getGeneralById(actor.id)!!
        nationWorld.applyGeneralDirtyFree(completed.copy(nationId = 2))
        assertEquals(false, TravelTurn(nationWorld, recorder, fixture.topology, fixture.metrics,
            MarchReactionPolicy.NON_BLOCKING).onTurn(actor.id))
        assertEquals(null, PersonalReturnStop.read(nationWorld.getGeneralById(actor.id)!!.meta))
        assertEquals(null, TravelState.read(nationWorld.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertEquals(route.first, nationWorld.positionOf(actor.id))
    }

    @Test fun `captured return remains stopped after release and corps still owns its earlier stage`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(218, 1, route.startCity, userId = "42", stats = GeneralStats(10, 10, 70, 70, 70))
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to
            CountyAssignment("dispatch-218", 99, 1, route.destinationCounty).toMetaValue()) +
            (PersonalTravelCondition.META_KEY to PersonalTravelCondition(0, 20).toMetaValue()))
        val enemy = fixture.person(219, 2, route.startCity, stats = GeneralStats(100, 100, 70, 70, 70))
        val world = fixture.world(listOf(actor to route.start, enemy to route.first),
            bugoks = listOf(fixture.unit(1219, enemy.id, 100)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, enemy.id, listOf(1219), route.destination)
        assertIs<TurnOutcome.Applied>(TravelHandler(world, recorder, fixture.topology, fixture.metrics)
            .handle(TravelInput.RETURN, actor.id, "{}", "return-218", 42))
        val captured = world.getGeneralById(actor.id)!!
        assertNotNull(CaptiveState.read(captured.meta))
        assertEquals(null, TravelState.read(captured.meta, fixture.topology, fixture.metrics))
        val stop = assertNotNull(PersonalReturnStop.read(captured.meta))
        val condition = PersonalTravelCondition.read(captured.meta)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(condition, PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        // Simulate the already-existing release boundary; do not change capture/release production logic.
        val beforeRelease = world.getGeneralById(actor.id)!!
        world.applyGeneralDirtyFree(beforeRelease.copy(meta = beforeRelease.meta - CaptiveState.META_KEY))
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(stop, PersonalReturnStop.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(condition!!.afterRest(), PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))

        val commander = fixture.person(220, 1, route.startCity).copy(meta = mapOf(
            PersonalReturnStop.META_KEY to PersonalReturnStop("prior-return", "dispatch-220").toMetaValue(),
            CountyAssignment.META_KEY to CountyAssignment("dispatch-220", 99, 1, route.destinationCounty).toMetaValue()))
        val corpsWorld = fixture.world(listOf(commander to route.start), bugoks = listOf(fixture.unit(1220, commander.id, 100)))
        val corpsRecorder = ChangeRecorder()
        fixture.deploy(corpsWorld, corpsRecorder, commander.id, listOf(1220), route.first)
        fixture.movement(corpsWorld, corpsRecorder).onTurn(commander.id, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.first, corpsWorld.positionOf(commander.id))
    }

    @Test
    fun `opaque order id yields one typed direct march on retry`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(212, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start))
        val handler = TravelHandler(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.first))
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
        val full = assertIs<opensamguk.logic.world.LandMarchPathResult.Resolved>(
            opensamguk.logic.world.StrategicPathResolver.resolveLandMarch(fixture.topology,
                opensamguk.logic.world.StrategicPathRequest(route.destination, route.start, 1),
                fixture.passage(), fixture.metrics)).path
        assertEquals(StrategicNodeRef.LandProvince(full.nodeKeys[1].removePrefix("land:")), saved.destination)
        assertEquals(1, saved.checkpoint.path.edgeIds.size)
        assertEquals(LandMarchStop.ARRIVED, saved.checkpoint.stop)
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
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.first))
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
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.first))
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
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.first))
        assertIs<TurnOutcome.Applied>(TravelHandler(world, recorder, fixture.topology,
            fixture.metrics).handle(TravelInput.MOVE, actor.id, raw, "move-210", 42))
        assertEquals("CAPTURED", (world.getGeneralById(actor.id)!!.meta[PersonalEncounter.REPLAY_KEY] as Map<*, *>)["outcome"])
        assertEquals(route.first, world.positionOf(actor.id))
        val held = assertNotNull(CaptiveState.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(enemy.id, held.captorGeneralId)
        assertEquals(world.positionOf(actor.id), world.positionOf(enemy.id))
        assertEquals((world.positionOf(actor.id) as opensamguk.logic.world.StrategicNodeRef.LandProvince).id,
            held.heldProvinceId)
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
        val raw = TravelInput.canonicalJson(TravelRequest(actor.id, TravelInput.MOVE, route.first))
        assertIs<TurnOutcome.Applied>(TravelHandler(world, recorder, fixture.topology,
            fixture.metrics, reaction).handle(TravelInput.MOVE, actor.id, raw, "move-208", 42))
        assertEquals(route.first, world.positionOf(enemy.id))
        assertEquals("WON", (world.getGeneralById(actor.id)!!.meta[PersonalEncounter.REPLAY_KEY] as Map<*, *>)["outcome"])
    }
}
