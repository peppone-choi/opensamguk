package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.*

class HwihaLegacyCourtHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()
    private fun input(id: String, args: String, requestId: String = "court-test") =
        TurnDaemonCommand.HwihaCourtInput(requestId, 501, 42, id, args)

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
