package opensamguk.engine.campaign

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.EngineGeneralActionPipelineBuilder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.CourtExpansionInput
import opensamguk.logic.input.DeployInput
import opensamguk.logic.input.DeployInputs
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A lawful queued decision is reassessed after a real battle captures its issuer. */
class CourtCaptiveBoundaryTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()

    private fun world(): Pair<InMemoryTurnWorld, ChangeRecorder> {
        val ruler = fixture.person(1, 1, route.startCity, userId = "42").copy(npcState = 0)
        val attacker = fixture.person(100, 2, route.startCity).copy(
            turnTime = ruler.turnTime.plusSeconds(60))
        return fixture.world(listOf(ruler to route.first, attacker to route.start),
            bugoks = listOf(fixture.unit(7, 1, 1), fixture.unit(1100, 100, 1000)),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity, level = 1),
                Nation(2, "N2", "#222222", level = 1)),
            cityChanges = { city -> if (city.id in setOf(route.startCity, route.destinationCounty))
                city.copy(nationId = 1) else city }) to ChangeRecorder()
    }

    private fun handler(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        ReservedTurnHandler(world,
            EngineGeneralActionPipelineBuilder(world, 200).registryFor(world.getGeneralById(1)!!), "fixture", 200,
            recorder = recorder, deploymentContext = fixture.topology to fixture.metrics,
            provinceCells = fixture.cells)

    private fun queueCapital(court: CourtHandler, world: InMemoryTurnWorld) {
        assertTrue(route.startCity in world.administrativeCountyIds)
        assertTrue(route.destinationCounty in world.administrativeCountyIds)
        assertEquals(1, world.getCityById(route.destinationCounty)?.nationId)
        assertEquals(route.startCity, world.getNationById(1)?.capitalCityId)
        assertTrue(route.startCity != route.destinationCounty, "the destination must differ from the current capital")
        assertFalse(CaptiveState.META_KEY in world.getGeneralById(1)!!.meta)
        val admitted = court.handle(TurnDaemonCommand.ImmediateInput("capital-before-capture", 1, 42,
            CourtExpansionInput.MOVE_CAPITAL, """{"countyId":${route.destinationCounty}}"""))
        assertTrue(admitted.ok, "a free ruler's actual moveCapital request must be admitted: $admitted")
        assertEquals("capital-before-capture", QueuedCourtAction.read(world.getGeneralById(1)!!.meta)?.requestId)
        assertEquals(route.startCity, world.getNationById(1)?.capitalCityId)
    }

    private fun issuerDue(world: InMemoryTurnWorld, handler: ReservedTurnHandler, recorder: ChangeRecorder) {
        val time = world.getGeneralById(1)!!.turnTime.plusSeconds(1)
        val turns = TurnDaemonLifecycle(world, handler,
            movementOf = { id, reserved, outcome -> fixture.movement(world, recorder).onTurn(id, reserved, outcome) },
            reservedActionOf = { CampaignWorldFixture.NO_INPUT }).runTick(time)
        assertEquals(listOf(1), turns.map { it.generalId }, "the other actor is not due in this issuer pass")
    }

    @Test
    fun `a free ruler's queued capital change still executes on its due turn`() {
        val (world, recorder) = world()
        val handler = handler(world, recorder)
        queueCapital(handler.courtHandler, world)

        issuerDue(world, handler, recorder)

        val execution = handler.courtHandler.takeExecutions().single()
        assertEquals("capital-before-capture", execution.requestId)
        assertEquals(42, execution.ownerUserId)
        assertTrue(execution.result.ok)
        assertEquals(route.destinationCounty, world.getNationById(1)?.capitalCityId)
        assertNull(QueuedCourtAction.read(world.getGeneralById(1)!!.meta))
        handler.courtHandler.onIssuerTurn(1)
        assertTrue(handler.courtHandler.takeExecutions().isEmpty(), "a consumed queue cannot execute twice")
    }

    private fun captured(): Triple<InMemoryTurnWorld, ReservedTurnHandler, ChangeRecorder> {
        val (world, recorder) = world()
        val deploy = DeployHandler(world, recorder, fixture.topology, fixture.metrics)
        assertIs<TurnOutcome.Applied>(deploy.handle(1,
            DeployInputs.canonicalJson(DeployInput(1, listOf(7), route.first)), "defender-corps", 42))
        assertIs<TurnOutcome.Applied>(deploy.handle(100,
            DeployInputs.canonicalJson(DeployInput(100, listOf(1100), route.destination)), null, null,
            npcSelected = true))
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(100, CampaignWorldFixture.NO_INPUT)
        assertNotNull(CorpsEncounter.read(world.getGeneralById(1)!!.meta, fixture.topology))
        val handler = handler(world, recorder)
        queueCapital(handler.courtHandler, world)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(100, CampaignWorldFixture.NO_INPUT)
        val captive = assertNotNull(CaptiveState.read(world.getGeneralById(1)!!.meta),
            "actual encounter settlement, not the troop ratio, must establish capture")
        assertEquals(100, captive.captorGeneralId)
        assertEquals(route.first.id, captive.heldProvinceId)
        assertEquals(world.positionOf(100), world.positionOf(1))
        assertNotNull(QueuedCourtAction.read(world.getGeneralById(1)!!.meta))
        return Triple(world, handler, recorder)
    }

    @Test
    fun `actual capture rejects an already queued capital decision without changing the nation`() {
        val (world, handler, recorder) = captured()
        val captive = assertNotNull(CaptiveState.read(world.getGeneralById(1)!!.meta))
        val nation = world.getNationById(1)
        val cities = world.listCities()
        val units = world.listBugoks()
        val position = world.generalPositionSnapshot()!!.stateFor(1)
        val otherActor = world.getGeneralById(100)
        val nationPatches = recorder.nationPatches()
        val cityPatches = recorder.cityPatches()
        world.consumeDirtyState()

        issuerDue(world, handler, recorder)

        val execution = handler.courtHandler.takeExecutions().single()
        assertEquals("capital-before-capture", execution.requestId)
        assertEquals(42, execution.ownerUserId)
        assertEquals(nation, world.getNationById(1), "captivity must prevent the queued capital mutation")
        assertEquals(cities, world.listCities())
        assertEquals(units, world.listBugoks())
        assertEquals(position, world.generalPositionSnapshot()!!.stateFor(1))
        assertEquals(captive, CaptiveState.read(world.getGeneralById(1)!!.meta))
        assertFalse(execution.result.ok)
        assertEquals("STATE_UNAVAILABLE", execution.result.code)
        assertEquals(otherActor, world.getGeneralById(100), "the not-due captor must remain unchanged")
        assertEquals(nationPatches, recorder.nationPatches())
        assertEquals(cityPatches, recorder.cityPatches())
        val dirty = world.consumeDirtyState()
        assertTrue(dirty.logs.none { it.eventKind == RecordKind.PERSONAL_APPLIED })
        assertNull(QueuedCourtAction.read(world.getGeneralById(1)!!.meta))
        assertStoredResult(world, recorder, execution)
        handler.courtHandler.onIssuerTurn(1)
        assertTrue(handler.courtHandler.takeExecutions().isEmpty(), "a rejected queue cannot execute twice")
        assertEquals(nation, world.getNationById(1))
    }
    private fun assertStoredResult(world: InMemoryTurnWorld, recorder: ChangeRecorder, execution: CourtExecution) {
        val expected = mapOf("requestId" to execution.requestId,
            "inputId" to CourtExpansionInput.MOVE_CAPITAL, "ok" to execution.result.ok,
            "code" to execution.result.code)
        assertEquals(expected, world.getGeneralById(1)!!.meta["courtLastExecution"])
        assertEquals(expected, recorder.generalPatches().single { it.id == 1 }.meta["courtLastExecution"])
    }

    @Test
    fun `ownership change remains forbidden even when the original issuer is actually captured`() {
        val (world, handler, recorder) = captured()
        val nation = world.getNationById(1)
        world.updateGeneral(world.getGeneralById(1)!!.copy(userId = "43"))

        issuerDue(world, handler, recorder)

        val execution = handler.courtHandler.takeExecutions().single()
        assertEquals("capital-before-capture", execution.requestId)
        assertEquals(42, execution.ownerUserId)
        assertFalse(execution.result.ok)
        assertEquals("FORBIDDEN", execution.result.code)
        assertEquals(nation, world.getNationById(1))
        assertNull(QueuedCourtAction.read(world.getGeneralById(1)!!.meta))
        assertStoredResult(world, recorder, execution)
    }

    @Test
    fun `a capture in another world does not block the free issuer with the same identity`() {
        val (capturedWorld, _, _) = captured()
        val capturedNation = capturedWorld.getNationById(1)
        val (freeWorld, recorder) = world()
        val handler = handler(freeWorld, recorder)
        queueCapital(handler.courtHandler, freeWorld)

        issuerDue(freeWorld, handler, recorder)

        val execution = handler.courtHandler.takeExecutions().single()
        assertTrue(execution.result.ok)
        assertEquals(route.destinationCounty, freeWorld.getNationById(1)?.capitalCityId)
        assertStoredResult(freeWorld, recorder, execution)
        assertEquals(capturedNation, capturedWorld.getNationById(1))
        assertNotNull(QueuedCourtAction.read(capturedWorld.getGeneralById(1)!!.meta))
        assertNotNull(CaptiveState.read(capturedWorld.getGeneralById(1)!!.meta))
    }

    @Test
    fun `a free issuer who loses lord authority cannot execute the earlier capital queue`() {
        val (world, recorder) = world()
        val handler = handler(world, recorder)
        queueCapital(handler.courtHandler, world)
        val actor = world.getGeneralById(1)!!
        world.updateGeneral(actor.copy(officerLevel = 1, meta = actor.meta + (LordStatus.META_KEY to false)))
        val nation = world.getNationById(1)

        issuerDue(world, handler, recorder)

        val execution = handler.courtHandler.takeExecutions().single()
        assertFalse(execution.result.ok)
        assertEquals("NOT_RULER", execution.result.code)
        assertEquals(nation, world.getNationById(1))
        assertStoredResult(world, recorder, execution)
    }

}
