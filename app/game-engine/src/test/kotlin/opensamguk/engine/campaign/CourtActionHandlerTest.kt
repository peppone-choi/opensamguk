package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.logic.input.*
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant

class CourtActionHandlerTest {
    private val fixture = CampaignWorldFixture()
    private fun input(id: String, args: String, requestId: String = "court-test") =
        TurnDaemonCommand.ImmediateInput(requestId, 501, 42, id, args)

    private fun catalogWithPlanned(inputId: String, originalState: String): InputCatalog {
        val resource = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json"))
        val original = resource.readText()
        val row = Regex("(\\\"inputId\\\":\\s*\\\"${Regex.escape(inputId)}\\\"[\\s\\S]*?\\\"deliveryState\\\":\\s*\\\")$originalState(\\\")")
        val planned = row.replace(original, "${'$'}1PLANNED${'$'}2")
        assertNotEquals(original, planned)
        return InputCatalog.parse(planned)
    }

    @Test fun `queued dispatch is rejected when its catalog row is no longer delivered`() {
        val route = fixture.route()
        val queued = QueuedDispatch("planned-dispatch", 42, 502, route.destinationCounty)
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (QueuedDispatch.META_KEY to queued.toMetaValue()))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = CourtHandler(world, ChangeRecorder(),
            catalog = catalogWithPlanned("court.dispatch", "HANDLER_READY"))

        handler.onIssuerTurn(501)

        assertFalse(QueuedDispatch.META_KEY in world.getGeneralById(501)!!.meta)
        val execution = handler.takeExecutions().single()
        assertEquals("planned-dispatch", execution.requestId)
        assertEquals(InputRejection.NOT_DELIVERED.name, execution.result.code)
        assertFalse(execution.result.ok)
    }

    @Test fun `queued legacy court input is rejected when its catalog row becomes planned`() {
        val route = fixture.route()
        val queued = QueuedCourtAction("planned-release", 42, "court.releaseCorps", """{"targetGeneralId":502}""")
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (QueuedCourtAction.META_KEY to queued.toMetaValue()))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = CourtHandler(world, ChangeRecorder(),
            catalog = catalogWithPlanned("court.releaseCorps", "UI_READY"))

        handler.onIssuerTurn(501)

        assertFalse(QueuedCourtAction.META_KEY in world.getGeneralById(501)!!.meta)
        val execution = handler.takeExecutions().single()
        assertEquals("planned-release", execution.requestId)
        assertEquals(InputRejection.NOT_DELIVERED.name, execution.result.code)
        assertFalse(execution.result.ok)
    }

    @Test fun `malformed queued dispatch is rejected and removed before the next issuer turn`() {
        val route = fixture.route()
        val queued = mapOf("requestId" to "bad-queue", "ownerUserId" to 42,
            "targetGeneralId" to 502, "countyId" to "invalid")
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (QueuedDispatch.META_KEY to queued))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = CourtHandler(world, ChangeRecorder())

        handler.onIssuerTurn(501)

        assertFalse(QueuedDispatch.META_KEY in world.getGeneralById(501)!!.meta)
        val execution = handler.takeExecutions().single()
        assertEquals("bad-queue", execution.requestId)
        assertEquals("STATE_UNAVAILABLE", execution.result.code)
        assertFalse(execution.result.ok)
    }

    @Test fun `malformed reward legacy and stratagem queues are rejected independently`() {
        val route = fixture.route()
        for ((key, inputId) in listOf(
            QueuedReward.META_KEY to RewardInput.INPUT_ID,
            QueuedCourtAction.META_KEY to "court.releaseCorps",
            QueuedStratagemAction.META_KEY to "stratagem.lastStand",
        )) {
            val queued = mapOf("requestId" to "bad-$key", "ownerUserId" to 42,
                "inputId" to inputId, "invalid" to true)
            val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
                it.copy(meta = it.meta + (key to queued))
            }
            val world = fixture.world(listOf(ruler to route.start), nations = listOf(
                Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
            val handler = CourtHandler(world, ChangeRecorder())

            handler.onIssuerTurn(501)

            assertFalse(key in world.getGeneralById(501)!!.meta, key)
            val execution = handler.takeExecutions().single()
            assertEquals("bad-$key", execution.requestId)
            assertEquals("STATE_UNAVAILABLE", execution.result.code)
            assertEquals(inputId, execution.result.actionCode)
            if (key == QueuedStratagemAction.META_KEY)
                assertEquals("STRATAGEM", execution.result.commandKind)
        }
    }

    @Test fun `malformed stratagem without input id keeps stratagem result kind`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (QueuedStratagemAction.META_KEY to mapOf(
                "requestId" to "bad-stratagem", "ownerUserId" to 42, "invalid" to true)))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = CourtHandler(world, ChangeRecorder())

        handler.onIssuerTurn(501)

        val execution = handler.takeExecutions().single()
        assertEquals("stratagem.unknown", execution.result.actionCode)
        assertEquals("STRATAGEM", execution.result.commandKind)
    }

    @Test fun `malformed queued decision does not stop the next general in the lifecycle`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (QueuedDispatch.META_KEY to mapOf(
                "requestId" to "bad-queue", "ownerUserId" to 42, "targetGeneralId" to 502, "countyId" to "invalid")))
        }
        val next = fixture.person(502, 1, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(ruler to route.start, next to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "fixture", 200)
        val lifecycle = TurnDaemonLifecycle(world, handler, reservedActionOf = { CampaignWorldFixture.NO_INPUT })

        val handled = lifecycle.runTick(Instant.parse("0200-01-01T03:00:01Z"))

        assertEquals(setOf(501, 502), handled.map { it.generalId }.toSet())
        assertTrue(world.getGeneralById(501)!!.turnTime > ruler.turnTime)
        assertTrue(world.getGeneralById(502)!!.turnTime > next.turnTime)
        assertFalse(QueuedDispatch.META_KEY in world.getGeneralById(501)!!.meta)
    }

    @Test fun `institution is rejected without a defined treasury model`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity, gold = 100, tech = 20.0),
            Nation(2, "N2", "#222222")))
        val handler = CourtHandler(world, ChangeRecorder())
        assertEquals(InputRejection.NOT_DELIVERED.name, handler.handle(input("court.institution", "{}")).code)
        handler.onIssuerTurn(501)
        assertEquals(20.0, world.getNationById(1)!!.tech)
        assertEquals(100, world.getNationById(1)!!.gold)
        assertTrue(handler.takeExecutions().isEmpty())
    }

    @Test fun `capital relocation and county abandonment use owned administrative counties`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(ruler to route.start),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity), Nation(2, "N2", "#222222")),
            cityChanges = { city -> if (city.id in setOf(route.startCity, route.destinationCounty)) city.copy(nationId = 1) else city })
        val handler = CourtHandler(world, ChangeRecorder())
        assertTrue(handler.handle(input("court.moveCapital", """{"countyId":${route.destinationCounty}}""", "move-capital")).ok)
        handler.onIssuerTurn(501)
        assertEquals(route.destinationCounty, world.getNationById(1)!!.capitalCityId)
        assertTrue(handler.takeExecutions().single().result.ok)
        assertTrue(handler.handle(input("court.abandonCounty", """{"countyId":${route.startCity}}""", "abandon")).ok)
        handler.onIssuerTurn(501)
        assertEquals(0, world.getCityById(route.startCity)!!.nationId)
        assertTrue(handler.takeExecutions().single().result.ok)
    }

    @Test fun `war is rejected before an envoy or diplomacy state can be consumed`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity),
            Nation(2, "N2", "#222222", capitalCityId = route.destinationCounty)))
        val handler = CourtHandler(world, ChangeRecorder())
        world.updateDiplomacy(1, 2, 2, 0)
        world.updateDiplomacy(2, 1, 2, 0)
        assertEquals(InputRejection.NOT_DELIVERED.name,
            handler.handle(input("court.declareWar", """{"targetNationId":2}""", "war")).code)
        handler.onIssuerTurn(501)
        assertEquals(2, world.getDiplomacy(1, 2)!!.state)
        assertEquals(2, world.getDiplomacy(2, 1)!!.state)
        assertTrue(handler.takeExecutions().isEmpty())
    }

    @Test fun `corps release persists metadata removal for owner and commander`() {
        val route = fixture.route()
        val corps = DeployedCorps("corps-release", 501, 502, 51, 1, listOf(7), Phase(200, 1, 1))
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (DeploymentState.META_KEY to DeploymentState(listOf(corps)).toMetaValue()))
        }
        val commander = fixture.person(502, 1, route.startCity, lord = false).let {
            it.copy(meta = it.meta + (CorpsOrder.META_KEY to mapOf("stale" to true)) +
                (CorpsMarchState.META_KEY to mapOf("stale" to true)))
        }
        val world = fixture.world(listOf(ruler to route.start, commander to route.start),
            bugoks = listOf(fixture.unit(7, ruler.id, 1000)),
            retainers = listOf(Retainer(51, 501, "TEST", 502, commander.name, "lieutenant")))
        val recorder = ChangeRecorder()
        val handler = CourtHandler(world, recorder)
        assertTrue(handler.handle(input("court.releaseCorps", """{"targetGeneralId":502}""", "release")).ok)
        handler.onIssuerTurn(501)
        assertTrue(handler.takeExecutions().single().result.ok)
        assertNull(DeploymentState.read(world.getGeneralById(501)!!.meta))
        assertFalse(CorpsOrder.META_KEY in world.getGeneralById(502)!!.meta)
        assertFalse(CorpsMarchState.META_KEY in world.getGeneralById(502)!!.meta)
        assertEquals(setOf(501, 502), recorder.generalPatches().map { it.id }.toSet())
    }

    private fun deployedEncounterPair(): Pair<InMemoryTurnWorld, ChangeRecorder> {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val commander = fixture.person(502, 1, route.startCity, lord = false)
        val enemy = fixture.person(100, 2, route.startCity)
        val world = fixture.world(listOf(ruler to route.start, commander to route.start, enemy to route.first),
            bugoks = listOf(fixture.unit(7, 501, 1000).copy(commanderRetainerId = 51),
                fixture.unit(1100, 100, 100)),
            retainers = listOf(Retainer(51, 501, "TEST", 502, commander.name, "lieutenant")))
        val recorder = ChangeRecorder()
        assertIs<DeploymentExecution.Applied>(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics)
            .deploy("release-battle", DeploymentRequest(501, 51, listOf(7))))
        val order = CorpsOrder("release-battle", 501, 502, route.destination,
            fixture.topology.topologyRevision, fixture.topology.contentHash)
        world.updateGeneralMeta(recorder, world.getGeneralById(502)!!,
            world.getGeneralById(502)!!.meta + (CorpsOrder.META_KEY to order.toMetaValue()))
        fixture.deploy(world, recorder, 100, listOf(1100), route.first)
        return world to recorder
    }

    private fun enterReleaseEncounter(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        fixture.nextPhase(world)
        assertTrue(CorpsMarchTurn(world, recorder, fixture.topology, fixture.metrics, fixture.cells).onTurn(502))
        for (id in listOf(502, 100))
            assertNotNull(CorpsEncounter.read(world.getGeneralById(id)!!.meta, fixture.topology))
        assertNotNull(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection())
    }

    @Test fun `sealed encounter rejects corps release at admission and preserves deployment projection`() {
        val (world, recorder) = deployedEncounterPair()
        enterReleaseEncounter(world, recorder)
        val generals = world.listGenerals().associateBy { it.id }
        val units = world.listBugoks()
        val result = CourtHandler(world, recorder)
            .handle(input("court.releaseCorps", """{"targetGeneralId":502}"""))
        assertFalse(result.ok)
        assertEquals("BATTLE_PENDING", result.code)
        assertEquals(generals, world.listGenerals().associateBy { it.id })
        assertEquals(units, world.listBugoks())
        assertNotNull(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection())
    }

    @Test fun `release queued before encounter is rejected at execution without removing battle state`() {
        val (world, recorder) = deployedEncounterPair()
        val handler = CourtHandler(world, recorder)
        assertTrue(handler.handle(input("court.releaseCorps", """{"targetGeneralId":502}""", "queued-release")).ok)
        enterReleaseEncounter(world, recorder)
        val commander = world.getGeneralById(502)
        val enemy = world.getGeneralById(100)
        val deployment = world.getGeneralById(501)!!.meta[DeploymentState.META_KEY]
        val units = world.listBugoks()
        handler.onIssuerTurn(501)
        val result = handler.takeExecutions().single().result
        assertNotNull(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection(),
            "release must not orphan sealed encounter participants")
        assertFalse(result.ok)
        assertEquals("BATTLE_PENDING", result.code)
        assertFalse(QueuedCourtAction.META_KEY in world.getGeneralById(501)!!.meta)
        assertEquals(deployment, world.getGeneralById(501)!!.meta[DeploymentState.META_KEY])
        assertEquals(commander, world.getGeneralById(502))
        assertEquals(enemy, world.getGeneralById(100))
        assertEquals(units, world.listBugoks())
        assertNotNull(DeploymentExecutor(world, recorder, fixture.topology, fixture.metrics).projection())
    }

}
