package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.logic.input.*
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import java.time.Instant

class HwihaLegacyCourtHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()
    private fun input(id: String, args: String, requestId: String = "court-test") =
        TurnDaemonCommand.HwihaCourtInput(requestId, 501, 42, id, args)

    private fun catalogWithPlanned(inputId: String, originalState: String): HwihaInputCatalog {
        val resource = checkNotNull(javaClass.classLoader.getResource("command-catalog/hwiha-input-catalog.json"))
        val original = resource.readText()
        val row = Regex("(\\\"inputId\\\":\\s*\\\"${Regex.escape(inputId)}\\\"[\\s\\S]*?\\\"deliveryState\\\":\\s*\\\")$originalState(\\\")")
        val planned = row.replace(original, "${'$'}1PLANNED${'$'}2")
        assertNotEquals(original, planned)
        return HwihaInputCatalog.parse(planned)
    }

    @Test fun `queued dispatch is rejected when its catalog row is no longer delivered`() {
        val route = fixture.route()
        val queued = HwihaQueuedDispatch("planned-dispatch", 42, 502, route.destinationCounty)
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (HwihaQueuedDispatch.META_KEY to queued.toMetaValue()))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = HwihaCourtHandler(world, ChangeRecorder(),
            catalog = catalogWithPlanned("court.dispatch", "HANDLER_READY"))

        handler.onIssuerTurn(501)

        assertFalse(HwihaQueuedDispatch.META_KEY in world.getGeneralById(501)!!.meta)
        val execution = handler.takeExecutions().single()
        assertEquals("planned-dispatch", execution.requestId)
        assertEquals(InputRejection.NOT_DELIVERED.name, execution.result.code)
        assertFalse(execution.result.ok)
    }

    @Test fun `queued legacy court input is rejected when its catalog row becomes planned`() {
        val route = fixture.route()
        val queued = HwihaQueuedLegacyCourt("planned-release", 42, "court.releaseCorps", """{"targetGeneralId":502}""")
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (HwihaQueuedLegacyCourt.META_KEY to queued.toMetaValue()))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = HwihaCourtHandler(world, ChangeRecorder(),
            catalog = catalogWithPlanned("court.releaseCorps", "UI_READY"))

        handler.onIssuerTurn(501)

        assertFalse(HwihaQueuedLegacyCourt.META_KEY in world.getGeneralById(501)!!.meta)
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
            it.copy(meta = it.meta + (HwihaQueuedDispatch.META_KEY to queued))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = HwihaCourtHandler(world, ChangeRecorder())

        handler.onIssuerTurn(501)

        assertFalse(HwihaQueuedDispatch.META_KEY in world.getGeneralById(501)!!.meta)
        val execution = handler.takeExecutions().single()
        assertEquals("bad-queue", execution.requestId)
        assertEquals("STATE_UNAVAILABLE", execution.result.code)
        assertFalse(execution.result.ok)
    }

    @Test fun `malformed reward legacy and stratagem queues are rejected independently`() {
        val route = fixture.route()
        for ((key, inputId) in listOf(
            HwihaQueuedReward.META_KEY to HwihaRewardInput.INPUT_ID,
            HwihaQueuedLegacyCourt.META_KEY to "court.releaseCorps",
            HwihaQueuedLegacyStratagem.META_KEY to "stratagem.lastStand",
        )) {
            val queued = mapOf("requestId" to "bad-$key", "ownerUserId" to 42,
                "inputId" to inputId, "invalid" to true)
            val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
                it.copy(meta = it.meta + (key to queued))
            }
            val world = fixture.world(listOf(ruler to route.start), nations = listOf(
                Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
            val handler = HwihaCourtHandler(world, ChangeRecorder())

            handler.onIssuerTurn(501)

            assertFalse(key in world.getGeneralById(501)!!.meta, key)
            val execution = handler.takeExecutions().single()
            assertEquals("bad-$key", execution.requestId)
            assertEquals("STATE_UNAVAILABLE", execution.result.code)
            assertEquals(inputId, execution.result.actionCode)
            if (key == HwihaQueuedLegacyStratagem.META_KEY)
                assertEquals("STRATAGEM", execution.result.commandKind)
        }
    }

    @Test fun `malformed stratagem without input id keeps stratagem result kind`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (HwihaQueuedLegacyStratagem.META_KEY to mapOf(
                "requestId" to "bad-stratagem", "ownerUserId" to 42, "invalid" to true)))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = HwihaCourtHandler(world, ChangeRecorder())

        handler.onIssuerTurn(501)

        val execution = handler.takeExecutions().single()
        assertEquals("stratagem.unknown", execution.result.actionCode)
        assertEquals("STRATAGEM", execution.result.commandKind)
    }

    @Test fun `malformed queued decision does not stop the next general in the lifecycle`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (HwihaQueuedDispatch.META_KEY to mapOf(
                "requestId" to "bad-queue", "ownerUserId" to 42, "targetGeneralId" to 502, "countyId" to "invalid")))
        }
        val next = fixture.person(502, 1, route.startCity, userId = "43", lord = false)
        val world = fixture.world(listOf(ruler to route.start, next to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "fixture", 200)
        val lifecycle = TurnDaemonLifecycle(world, handler, reservedActionOf = { HwihaCampaignWorldFixture.NO_INPUT })

        val handled = lifecycle.runTick(Instant.parse("0200-01-01T03:00:01Z"))

        assertEquals(setOf(501, 502), handled.map { it.generalId }.toSet())
        assertTrue(world.getGeneralById(501)!!.turnTime > ruler.turnTime)
        assertTrue(world.getGeneralById(502)!!.turnTime > next.turnTime)
        assertFalse(HwihaQueuedDispatch.META_KEY in world.getGeneralById(501)!!.meta)
    }

    @Test fun `institution is rejected without a defined treasury model`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity, gold = 100, tech = 20.0),
            Nation(2, "N2", "#222222")))
        val handler = HwihaCourtHandler(world, ChangeRecorder())
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
        val handler = HwihaCourtHandler(world, ChangeRecorder())
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
        val handler = HwihaCourtHandler(world, ChangeRecorder())
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
        val corps = HwihaDeployedCorps("corps-release", 501, 502, 51, 1, listOf(7), HwihaPhase(200, 1, 1))
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (HwihaDeploymentState.META_KEY to HwihaDeploymentState(listOf(corps)).toMetaValue()))
        }
        val commander = fixture.person(502, 1, route.startCity, lord = false).let {
            it.copy(meta = it.meta + (HwihaCorpsOrder.META_KEY to mapOf("stale" to true)) +
                (HwihaCorpsMarchState.META_KEY to mapOf("stale" to true)))
        }
        val world = fixture.world(listOf(ruler to route.start, commander to route.start),
            bugoks = listOf(fixture.unit(7, ruler.id, 1000)),
            retainers = listOf(Retainer(51, 501, "TEST", 502, commander.name, "lieutenant")))
        val recorder = ChangeRecorder()
        val handler = HwihaCourtHandler(world, recorder)
        assertTrue(handler.handle(input("court.releaseCorps", """{"targetGeneralId":502}""", "release")).ok)
        handler.onIssuerTurn(501)
        assertTrue(handler.takeExecutions().single().result.ok)
        assertNull(HwihaDeploymentState.read(world.getGeneralById(501)!!.meta))
        assertFalse(HwihaCorpsOrder.META_KEY in world.getGeneralById(502)!!.meta)
        assertFalse(HwihaCorpsMarchState.META_KEY in world.getGeneralById(502)!!.meta)
        assertEquals(setOf(501, 502), recorder.generalPatches().map { it.id }.toSet())
    }
}
