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

    @Test fun `institution queues then spends treasury and improves technology once`() {
        val route = fixture.route()
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity, gold = 100, tech = 20.0),
            Nation(2, "N2", "#222222")))
        val handler = HwihaCourtHandler(world, ChangeRecorder())
        assertTrue(handler.handle(input("court.institution", "{}")).ok)
        assertEquals(20.0, world.getNationById(1)!!.tech)
        handler.onIssuerTurn(501)
        assertEquals(30.0, world.getNationById(1)!!.tech)
        assertEquals(0, world.getNationById(1)!!.gold)
        val execution = handler.takeExecutions().single().result
        assertTrue(execution.ok)
        assertEquals("court.institution", execution.inputResolved?.inputId)
        assertEquals("COURT_DECISION", execution.inputResolved?.kind)
        handler.onIssuerTurn(501)
        assertEquals(30.0, world.getNationById(1)!!.tech)
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

    @Test fun `war requires an arrived envoy and transitions both diplomacy directions`() {
        val route = fixture.route()
        val now = HwihaPhase(200, 1, 1)
        val ruler = fixture.person(501, 1, route.startCity, userId = "42")
        val plainEnvoy = fixture.person(502, 1, route.destinationCounty, lord = false)
        val world = fixture.world(listOf(ruler to route.start, plainEnvoy to route.destination),
            nations = listOf(Nation(1, "N1", "#111111", capitalCityId = route.startCity),
                Nation(2, "N2", "#222222", capitalCityId = route.destinationCounty)),
            retainers = listOf(Retainer(51, 501, "TEST", 502, plainEnvoy.name, "guest")))
        val handler = HwihaCourtHandler(world, ChangeRecorder())
        world.updateDiplomacy(1, 2, 2, 0)
        world.updateDiplomacy(2, 1, 2, 0)
        assertEquals(HwihaLegacyCourtFailure.ENVOY_REQUIRED.name,
            handler.handle(input("court.declareWar", """{"targetNationId":2}""", "without-envoy")).code)
        val order = HwihaPlacementOrder("envoy-51", 501, 51, PlacementPost.ENVOY, PlacementTarget.Nation(2), now)
        val envoy = world.getGeneralById(502)!!
        world.applyGeneralDirtyFree(envoy.copy(meta = envoy.meta + (HwihaPlacementState.META_KEY to
            HwihaPlacementState(HwihaActivePlacement(order, now, now), null).toMetaValue())))
        assertTrue(handler.handle(input("court.declareWar", """{"targetNationId":2}""", "with-envoy")).ok)
        handler.onIssuerTurn(501)
        assertEquals(1, world.getDiplomacy(1, 2)!!.state)
        assertEquals(1, world.getDiplomacy(2, 1)!!.state)
        assertTrue(handler.takeExecutions().single().result.ok)
    }
}
