package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.DirectInput
import opensamguk.logic.input.Phase

class CaptivePersonalActionTest {
    @Test fun `field and direct execution reject current captive marker before any cost`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        val captive = CaptiveState(802, route.start.id, Phase(200, 1, 1),
            "encounter-801").toMetaValue()
        for (marker in listOf(captive, mapOf("version" to 1), null)) {
            val actor = fixture.person(801, 1, route.startCity, userId = "42").copy(gold = 100,
                meta = mapOf(CaptiveState.META_KEY to marker))
            val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
                if (city.id == route.startCity) city.copy(nationId = 1,
                    meta = city.meta + (CountyWarehouse.META_KEY to
                        CountyWarehouse(city.id, 0, Resources(grain = 300)).toMetaValue())) else city
            })
            val beforeCity = world.getCityById(route.startCity)!!
            val beforeActor = world.getGeneralById(actor.id)!!
            val field = assertIs<TurnOutcome.Rejected>(FieldHandler(world, ChangeRecorder(),
                DomesticContext()).handle(FieldInput.FARM, actor.id, "{}", "farm-held", 42))
            val direct = assertIs<TurnOutcome.Rejected>(DirectActionHandler(world, ChangeRecorder(),
                DomesticContext(cityConst = fixture.bundle.cityConst)).handle(DirectInput.GRAIN, actor.id,
                """{"side":"BUY","amount":1}""", "grain-held", 42))
            assertEquals("STATE_UNAVAILABLE", field.code)
            assertEquals("STATE_UNAVAILABLE", direct.code)
            assertEquals(beforeCity.agriculture, world.getCityById(route.startCity)!!.agriculture)
            assertEquals(beforeCity.meta, world.getCityById(route.startCity)!!.meta)
            assertEquals(beforeActor.gold, world.getGeneralById(actor.id)!!.gold)
            assertEquals(beforeActor.rice, world.getGeneralById(actor.id)!!.rice)
        }
    }
}
