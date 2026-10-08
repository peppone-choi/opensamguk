package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.input.TravelInput
import opensamguk.logic.input.TravelRequest
import opensamguk.logic.input.TravelState
import opensamguk.logic.input.TravelFailure
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.PersonalTravelCondition
import opensamguk.logic.input.PersonalTravelPolicyHold
import opensamguk.logic.input.MarchCheckpoint
import opensamguk.logic.input.RecordKind
import opensamguk.logic.input.ForcedMarchTempo
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.LandMarchCursor
import opensamguk.logic.input.LandPassageState
import opensamguk.logic.input.CountyAssignment
import opensamguk.logic.input.MarchState
import opensamguk.logic.input.RoadFort
import opensamguk.logic.input.RoadFortState
import opensamguk.logic.world.LandMarchEntry
import opensamguk.logic.world.LandMarchStop
import opensamguk.logic.input.Phase
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import opensamguk.engine.turn.PerTurnOverlay

class TravelExecutorTest {
    /** Explicit old persisted fixture, independent of today's new-order policy. */
    private fun legacyPartial(world: opensamguk.engine.turn.InMemoryTurnWorld, fixture: CampaignWorldFixture,
        metrics: LandMarchMetricSnapshot, actorId: Int, orderId: String,
        destination: opensamguk.logic.world.StrategicNodeRef.LandProvince, inputId: String): TravelState {
        val origin = world.positionOf(actorId) as opensamguk.logic.world.StrategicNodeRef.LandProvince
        val path = assertIs<opensamguk.logic.world.LandMarchPathResult.Resolved>(
            opensamguk.logic.world.StrategicPathResolver.resolveLandMarch(fixture.topology,
                opensamguk.logic.world.StrategicPathRequest(origin, destination, 1), fixture.passage(), metrics)).path
        val movement = assertIs<opensamguk.logic.world.LandMarchAdvance.Advanced>(
            opensamguk.logic.world.LandMarchProgress.advance(fixture.topology, metrics, fixture.passage(),
                path, LandMarchCursor(path.pathHash), origin, 1, 1) { LandMarchEntry.CLEAR })
        assertEquals(0, movement.reachedNodes.size)
        val phase = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        val actor = world.getGeneralById(actorId)!!
        val state = TravelState(orderId, inputId, destination,
            MarchCheckpoint(path, movement.cursor, phase, movement.stop), CountyAssignment.read(actor.meta)?.dispatchId)
        world.applyGeneralDirtyFree(actor.copy(meta = actor.meta + (TravelState.META_KEY to state.toMetaValue())))
        return state
    }

    @Test fun `legacy return rejected by encounter captivity or missing passage keeps its exact checkpoint and owns idle movement`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val path = assertIs<opensamguk.logic.world.LandMarchPathResult.Resolved>(
            opensamguk.logic.world.StrategicPathResolver.resolveLandMarch(fixture.topology,
                opensamguk.logic.world.StrategicPathRequest(route.start, route.destination, 1),
                fixture.passage(), fixture.metrics)).path
        for (guard in listOf("encounter", "captive", "passage")) {
            val cursor = if (guard == "encounter") LandMarchCursor(path.pathHash, 1) else LandMarchCursor(path.pathHash, 0, 1)
            val saved = TravelState("legacy-return-guard", TravelInput.RETURN, route.destination,
                MarchCheckpoint(path, cursor, Phase(200, 1, 1), if (guard == "encounter")
                    LandMarchStop.ENCOUNTER else LandMarchStop.BUDGET_EXHAUSTED), "dispatch-221")
            val base = fixture.person(221, 1, route.startCity)
            val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to
                CountyAssignment("dispatch-221", 99, 1, route.destinationCounty).toMetaValue()) +
                (TravelState.META_KEY to saved.toMetaValue()) +
                (if (guard == "captive") mapOf(CaptiveState.META_KEY to
                    CaptiveState(222, route.start.id, Phase(200, 1, 1), "guard-capture").toMetaValue()) else emptyMap()))
            val origin = if (guard == "encounter") route.first else route.start
            val world = fixture.world(listOf(actor to origin))
            if (guard == "passage") world.setGameEnvValue(LandPassageState.META_KEY, mapOf("version" to 1))
            fixture.nextPhase(world)
            world.consumeDirtyState()
            val recorder = ChangeRecorder()
            val ownsMovement = TravelTurn(world, recorder, fixture.topology, fixture.metrics,
                MarchReactionPolicy.NON_BLOCKING).onTurn(actor.id)
            assertEquals(saved.toMetaValue(), world.getGeneralById(actor.id)!!.meta[TravelState.META_KEY])
            assertEquals(true, ownsMovement)
            assertEquals(actor, world.getGeneralById(actor.id))
            fixture.movement(world, recorder).onTurn(actor.id, CampaignWorldFixture.NO_INPUT)
            assertEquals(actor, world.getGeneralById(actor.id))
            assertEquals(origin, world.positionOf(actor.id))
            val dirty = world.consumeDirtyState()
            assertEquals(0, dirty.generals.size)
            assertEquals(0, dirty.logs.size)
            assertEquals(0, dirty.gameEvents.size)
        }
    }

    @Test fun `exhausted policy hold rests once without resuming and a payable new order clears the marker`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(115, 1, route.startCity)
        val actor = base.copy(meta = base.meta + (PersonalTravelCondition.META_KEY to PersonalTravelCondition(100, 0).toMetaValue()))
        val metrics = LandMarchMetricSnapshot(fixture.topology, fixture.metrics.tilesHash,
            fixture.metrics.edgesById.values.map { it.copy(distanceMm = 30_000_000, costMm = maxOf(it.costMm, 30_000_000)) })
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val executor = TravelExecutor(world, recorder, fixture.topology, metrics)
        val original = legacyPartial(world, fixture, metrics, actor.id, "old-exhausted",
            route.destination, TravelInput.FORCED_MARCH)
        val before = world.getGeneralById(actor.id)!!
        world.applyGeneralDirtyFree(before.copy(meta = before.meta + (TravelState.META_KEY to original.toMetaValue())))
        fixture.nextPhase(world)
        world.consumeDirtyState()
        val turn = TravelTurn(world, recorder, fixture.topology, metrics, MarchReactionPolicy.NON_BLOCKING)
        assertEquals(true, turn.onTurn(actor.id))
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(original.toMetaValue(), world.getGeneralById(actor.id)!!.meta[TravelState.META_KEY])
        assertEquals(PersonalTravelCondition(90, 5), PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        val marker = PersonalTravelPolicyHold.read(world.getGeneralById(actor.id)!!.meta)!!
        assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED, marker.reason)
        assertEquals(1, world.consumeDirtyState().logs.count { it.eventKind == RecordKind.INPUT_REJECTED })
        assertEquals(true, turn.onTurn(actor.id))
        assertEquals(PersonalTravelCondition(90, 5), PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(marker, PersonalTravelPolicyHold.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(0, world.consumeDirtyState().logs.count { it.eventKind == RecordKind.INPUT_REJECTED })
        val moved = assertIs<TravelExecution.Applied>(executor.start("new-payable",
            TravelRequest(actor.id, TravelInput.FORCED_MARCH, route.first), route.first, 1) { LandMarchEntry.CLEAR })
        assertEquals(LandMarchStop.ARRIVED, moved.movement.stop)
        assertEquals(PersonalTravelCondition(100, 0), moved.condition)
        assertEquals(route.first, world.positionOf(actor.id))
        assertNull(PersonalTravelPolicyHold.read(world.getGeneralById(actor.id)!!.meta))
    }

    private fun costs(fixture: CampaignWorldFixture, costMm: Long) = LandMarchMetricSnapshot(fixture.topology,
        fixture.metrics.tilesHash, fixture.metrics.edgesById.values.map { it.copy(costMm = maxOf(it.costMm, costMm)) })

    @Test fun `stopped adjacent move and old partially paid single edge both resume in one phase`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(111, 1, route.startCity)
        val metrics = costs(fixture, 500_000_000)
        for (oldPartial in listOf(false, true)) {
            val world = fixture.world(listOf(actor to route.start))
            val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, metrics)
            val original = if (oldPartial) legacyPartial(world, fixture, metrics, actor.id,
                "single-111", route.first, TravelInput.MOVE) else assertIs<TravelExecution.Applied>(
                executor.start("single-111", TravelRequest(actor.id, TravelInput.MOVE, route.first),
                    route.first, 1) { LandMarchEntry.UNAVAILABLE }).state
            val before = world.getGeneralById(actor.id)!!
            world.applyGeneralDirtyFree(before.copy(meta = before.meta + (TravelState.META_KEY to original.toMetaValue())))
            assertEquals(if (oldPartial) 1L else 0L, original.checkpoint.cursor.paidMm)
            assertEquals(route.start, world.positionOf(actor.id))
            fixture.nextPhase(world)
            val resumed = assertIs<TravelExecution.Applied>(executor.resume(actor.id, 1) { LandMarchEntry.CLEAR })
            assertEquals(LandMarchStop.ARRIVED, resumed.movement.stop)
            assertEquals(route.first, world.positionOf(actor.id))
            assertEquals(original.orderId, resumed.state.orderId)
            assertEquals(original.checkpoint.path.pathHash, resumed.state.checkpoint.path.pathHash)
            assertEquals(500_000_000L - original.checkpoint.cursor.paidMm, resumed.movement.spentMm)
            assertEquals(0L, resumed.movement.cursor.paidMm)
            assertNull(PersonalTravelPolicyHold.read(world.getGeneralById(actor.id)!!.meta))
        }
    }

    @Test fun `legacy long move and out of policy forced routes hold without losing cursor or repeated logs`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(112, 1, route.startCity)
        val metrics = costs(fixture, 500_000_000)
        for (inputId in listOf(TravelInput.MOVE, TravelInput.FORCED_MARCH, TravelInput.RETURN)) {
            val savedActor = if (inputId == TravelInput.RETURN) actor.copy(meta = actor.meta +
                (CountyAssignment.META_KEY to CountyAssignment("dispatch-112", 99, 1, route.destinationCounty).toMetaValue())) else actor
            val world = fixture.world(listOf(savedActor to route.start))
            val recorder = ChangeRecorder()
            val executor = TravelExecutor(world, recorder, fixture.topology, metrics)
            val original = legacyPartial(world, fixture, metrics, actor.id, "legacy-112", route.destination, inputId)
            assertEquals(2, original.checkpoint.path.edgeIds.size)
            val before = world.getGeneralById(actor.id)!!
            world.applyGeneralDirtyFree(before.copy(meta = before.meta + (TravelState.META_KEY to original.toMetaValue())))
            world.consumeDirtyState()
            fixture.nextPhase(world)
            val reason = if (inputId == TravelInput.FORCED_MARCH) TravelFailure.FORCED_DURATION_EXCEEDED else TravelFailure.TRAVEL_POLICY_CHANGED
            assertEquals(reason, assertIs<TravelExecution.PolicyHeld>(executor.resume(actor.id, 1) {
                error("Held policy must not inspect or enter another province")
            }).reason)
            assertEquals(route.start, world.positionOf(actor.id))
            assertEquals(original.toMetaValue(), world.getGeneralById(actor.id)!!.meta[TravelState.META_KEY])
            val turn = TravelTurn(world, recorder, fixture.topology, metrics, MarchReactionPolicy.NON_BLOCKING)
            assertEquals(true, turn.onTurn(actor.id))
            val heldMeta = world.getGeneralById(actor.id)!!.meta
            assertEquals(original.toMetaValue(), heldMeta[TravelState.META_KEY])
            assertEquals(reason, PersonalTravelPolicyHold.read(heldMeta)!!.reason)
            val first = world.consumeDirtyState()
            assertEquals(1, first.logs.count { it.eventKind == RecordKind.INPUT_REJECTED })
            assertEquals(actor.id, first.logs.single { it.eventKind == RecordKind.INPUT_REJECTED }.generalId)
            assertEquals("general", first.logs.single { it.eventKind == RecordKind.INPUT_REJECTED }.scope)
            fixture.nextPhase(world)
            assertEquals(true, turn.onTurn(actor.id))
            assertEquals(heldMeta, world.getGeneralById(actor.id)!!.meta)
            assertEquals(0, world.consumeDirtyState().logs.count { it.eventKind == RecordKind.INPUT_REJECTED })
            val failed = executor.start("invalid-new", TravelRequest(actor.id, TravelInput.MOVE, route.destination),
                route.destination, 1) { error("Invalid reservation cannot advance") }
            assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelExecution.Rejected>(failed).reason)
            assertEquals(heldMeta, world.getGeneralById(actor.id)!!.meta)
            assertIs<TravelExecution.Applied>(executor.start("valid-new", TravelRequest(actor.id, TravelInput.MOVE, route.first),
                route.first, 1) { LandMarchEntry.CLEAR })
            assertNull(PersonalTravelPolicyHold.read(world.getGeneralById(actor.id)!!.meta))
            assertEquals(route.first, world.positionOf(actor.id))
        }
    }

    @Test fun `forced raw cost refusal leaves position metadata and recorder untouched`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(113, 1, route.startCity)
        val actor = base.copy(meta = base.meta + (PersonalTravelCondition.META_KEY to PersonalTravelCondition(100, 0).toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val before = world.getGeneralById(actor.id)!!
        val rejected = assertIs<TravelExecution.Rejected>(TravelExecutor(world, recorder, fixture.topology, fixture.metrics).start(
            "unpaid-113", TravelRequest(actor.id, TravelInput.FORCED_MARCH, route.first), route.first, 1) {
            error("Unpaid input must be rejected before movement")
        })
        assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED, rejected.reason)
        assertEquals(before, world.getGeneralById(actor.id))
        assertEquals(route.start, world.positionOf(actor.id))
        assertEquals(emptyList(), recorder.generalPatches())
        assertEquals(emptyList(), world.consumeDirtyState().gameEvents)
    }

    @Test fun `two phase forced execution spends existing physical costs once and rechecks remaining payment`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(114, 1, route.startCity)
        val metrics = costs(fixture, 370_000_000)
        val world = fixture.world(listOf(actor to route.start))
        val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, metrics)
        val request = TravelRequest(actor.id, TravelInput.FORCED_MARCH, route.destination)
        val first = assertIs<TravelExecution.Applied>(executor.start("two-114", request, route.destination, 1) { LandMarchEntry.CLEAR })
        assertEquals(740_000_000L, first.state.checkpoint.path.totalCostMm)
        assertEquals(LandMarchStop.BUDGET_EXHAUSTED, first.movement.stop)
        assertEquals(1, first.movement.cursor.edgeIndex)
        val condition = first.condition!!
        assertIs<TravelExecution.AlreadyProcessed>(executor.resume(actor.id, 1) { error("Phase replay cannot spend") })
        assertEquals(condition, PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        fixture.nextPhase(world)
        val before = world.getGeneralById(actor.id)!!
        val exhausted = PersonalTravelCondition(100, 0, condition.forcedDistanceRemainderMm)
        world.applyGeneralDirtyFree(before.copy(meta = before.meta + (PersonalTravelCondition.META_KEY to exhausted.toMetaValue())))
        val held = assertIs<TravelExecution.PolicyHeld>(executor.resume(actor.id, 1) { error("Unpaid remainder cannot advance") })
        assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED, held.reason)
        assertEquals(first.state.toMetaValue(), world.getGeneralById(actor.id)!!.meta[TravelState.META_KEY])
        world.applyGeneralDirtyFree(before)
        val second = assertIs<TravelExecution.Applied>(executor.resume(actor.id, 1) { LandMarchEntry.CLEAR })
        assertEquals(LandMarchStop.ARRIVED, second.movement.stop)
        assertEquals(route.destination, world.positionOf(actor.id))
        val final = PersonalTravelCondition.INITIAL.afterForcedMarch(LandMarchCursor(first.state.checkpoint.path.pathHash),
            second.movement.cursor, first.state.checkpoint.path, metrics)
        assertEquals(final, second.condition)
        assertEquals(final, PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
    }

    @Test fun `move arrives on its adjacent edge in one phase regardless of terrain cost`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(100, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val rough = opensamguk.logic.world.LandMarchMetricSnapshot(fixture.topology,
            fixture.metrics.tilesHash, fixture.metrics.edgesById.values.map {
                it.copy(costMm = maxOf(it.costMm, 500_000_000L))
            })
        val recorder = ChangeRecorder()
        val executor = TravelExecutor(world, recorder, fixture.topology, rough)
        val request = TravelRequest(actor.id, TravelInput.MOVE, route.first)
        val result = assertIs<TravelExecution.Applied>(executor.start("adjacent-100", request, route.first, 1) {
            LandMarchEntry.CLEAR
        })
        assertEquals(1, result.state.checkpoint.path.edgeIds.size)
        assertEquals(500_000_000L, result.state.checkpoint.path.totalCostMm)
        assertEquals(LandMarchStop.ARRIVED, result.movement.stop)
        assertEquals(route.first, world.positionOf(actor.id))
        assertEquals(listOf(route.first), result.movement.reachedNodes)
        assertEquals(0L, result.movement.cursor.paidMm)
        assertNull(result.condition)
        assertNull(PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
        assertIs<TravelExecution.AlreadyProcessed>(executor.start("adjacent-100", request, route.first, 1) {
            error("A retry must not enter the destination again")
        })
        assertEquals(route.first, world.positionOf(actor.id))
        val other = fixture.world(listOf(actor to route.start))
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelExecution.Rejected>(
            TravelExecutor(other, ChangeRecorder(), fixture.topology, rough).start("far-100",
                request.copy(destination = route.destination), route.destination, 1) { LandMarchEntry.CLEAR }).reason)
        assertEquals(route.start, other.positionOf(actor.id))
    }

    @Test
    fun `captive marker blocks travel start and a pending march before advance`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(105, 1, route.startCity)
        val markers = listOf(CaptiveState(106, route.start.id, Phase(200, 1, 1),
            "encounter-105").toMetaValue(), mapOf("version" to 1), null)
        for (marker in markers) {
            val held = actor.copy(meta = actor.meta + (CaptiveState.META_KEY to marker))
            val world = fixture.world(listOf(held to route.start))
            val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics)
            val rejected = assertIs<TravelExecution.Rejected>(executor.start("travel-105",
                TravelRequest(actor.id, TravelInput.MOVE, route.first), route.first, 1) {
                LandMarchEntry.UNAVAILABLE
            })
            assertEquals(TravelFailure.STATE_UNAVAILABLE, rejected.reason)
            assertEquals(route.start, world.positionOf(actor.id))
        }

        val world = fixture.world(listOf(actor to route.start))
        val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        assertIs<TravelExecution.Applied>(executor.start("travel-105",
            TravelRequest(actor.id, TravelInput.MOVE, route.first), route.first, 1) {
            LandMarchEntry.UNAVAILABLE
        })
        val before = world.getGeneralById(actor.id)!!
        world.applyGeneralDirtyFree(before.copy(meta = before.meta +
            (CaptiveState.META_KEY to mapOf("version" to 1))))
        fixture.nextPhase(world)
        assertEquals(TravelFailure.STATE_UNAVAILABLE, assertIs<TravelExecution.Rejected>(
            executor.resume(actor.id, fixture.metrics.edgesById.values.first().costMm) {
                LandMarchEntry.CLEAR
            }).reason)
        assertEquals(route.start, world.positionOf(actor.id))
    }

    @Test
    fun `hostile road fort seals an existing direct route before the next advance`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(104, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val request = TravelRequest(actor.id, TravelInput.MOVE, route.first)
        val started = assertIs<TravelExecution.Applied>(
            executor.start("travel-104", request, route.first, 1) { LandMarchEntry.UNAVAILABLE })
        val edgeId = started.state.checkpoint.path.edgeIds.first()
        val fort = RoadFort(RoadFort.siteId(edgeId, 0, 0), edgeId, route.start.id,
            0, 0, 2, 100, 100)
        world.setGameEnvValue(RoadFortState.META_KEY, RoadFortState.toMetaValue(listOf(fort)))
        fixture.nextPhase(world)

        val blocked = assertIs<TravelExecution.Applied>(executor.resume(actor.id,
            fixture.metrics.edgesById.getValue(edgeId).costMm) { LandMarchEntry.CLEAR })
        assertEquals(LandMarchStop.EDGE_BLOCKED, blocked.movement.stop)
        assertEquals(route.start, world.positionOf(actor.id))
    }

    @Test
    fun `return enters exactly one adjacent province in one phase and replay never advances again`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val base = fixture.person(101, 1, route.startCity)
        val actor = base.copy(meta = base.meta + (CountyAssignment.META_KEY to
            CountyAssignment("dispatch-101", 99, 1, route.destinationCounty).toMetaValue()))
        val world = fixture.world(listOf(actor to route.start))
        val metrics = costs(fixture, 500_000_000)
        val expectedPath = assertIs<opensamguk.logic.world.LandMarchPathResult.Resolved>(
            opensamguk.logic.world.StrategicPathResolver.resolveLandMarch(fixture.topology,
                opensamguk.logic.world.StrategicPathRequest(route.start, route.destination, 1), fixture.passage(), metrics)).path
        val next = opensamguk.logic.world.StrategicNodeRef.LandProvince(expectedPath.nodeKeys[1].removePrefix("land:"))
        val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, metrics)
        val request = TravelRequest(actor.id, TravelInput.RETURN, null)
        val applied = assertIs<TravelExecution.Applied>(
            executor.start("travel-101", request, route.destination, 1) { LandMarchEntry.CLEAR })
        assertEquals(LandMarchStop.ARRIVED, applied.state.checkpoint.stop)
        assertEquals(next, applied.state.destination)
        assertEquals(listOf(next), applied.movement.reachedNodes)
        assertEquals(1, applied.state.checkpoint.path.edgeIds.size)
        assertEquals(500_000_000L, applied.movement.spentMm)
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.start("travel-101", request, route.destination, 1) { LandMarchEntry.CLEAR })
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.start("travel-102", request, route.destination, 1) { LandMarchEntry.CLEAR })
        fixture.nextPhase(world)
        assertIs<TravelExecution.NoOrder>(executor.resume(actor.id, 1) { error("Completed return cannot resume") })
        assertEquals(next, world.positionOf(actor.id))
        assertEquals("travel-101", TravelState.read(world.getGeneralById(actor.id)!!.meta,
            fixture.topology, metrics)?.orderId)
    }

    @Test
    fun `forced march persists personal cost once on the recorder path`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(102, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val executor = TravelExecutor(world, ChangeRecorder(), fixture.topology, fixture.metrics)
        val request = TravelRequest(actor.id, TravelInput.FORCED_MARCH, route.destination)
        val first = assertIs<TravelExecution.Applied>(
            executor.start("forced-102", request, route.destination, 45_000_000) { LandMarchEntry.CLEAR })
        val condition = PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta)
        assertEquals(first.condition, condition)
        assertIs<TravelExecution.AlreadyProcessed>(
            executor.start("forced-102", request, route.destination, 45_000_000) { LandMarchEntry.CLEAR })
        assertEquals(condition, PersonalTravelCondition.read(world.getGeneralById(actor.id)!!.meta))
    }

    @Test
    fun `direct movement clears an aligned assignment march before the next projection`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val actor = fixture.person(103, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start))
        val recorder = ChangeRecorder()
        val executor = TravelExecutor(world, recorder, fixture.topology, fixture.metrics)
        val request = TravelRequest(actor.id, TravelInput.MOVE, route.first)
        val initial = assertIs<TravelExecution.Applied>(
            executor.start("travel-103", request, route.first, 1) { LandMarchEntry.UNAVAILABLE })
        val before = world.getGeneralById(actor.id)!!
        val march = MarchState(CountyAssignment("dispatch-103", route.destinationCounty, 1, 1),
            initial.state.checkpoint.path, initial.state.checkpoint.cursor, Phase(200, 1, 1),
            LandMarchStop.BUDGET_EXHAUSTED)
        val withMarch = before.copy(meta = before.meta + (MarchState.META_KEY to march.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(withMarch))
        world.applyGeneralDirtyFree(withMarch)
        fixture.nextPhase(world)

        assertIs<TravelExecution.Applied>(executor.resume(actor.id,
            fixture.metrics.edgesById.getValue(initial.state.checkpoint.path.edgeIds.first()).costMm) { LandMarchEntry.CLEAR })
        assertNull(MarchState.read(world.getGeneralById(actor.id)!!.meta, fixture.topology, fixture.metrics))
        assertNotNull(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection())
    }
}
