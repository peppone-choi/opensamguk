package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.input.MilitaryInput
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn

class CityMilitaryHandlerTest {
    private val fixture = CampaignWorldFixture()

    @Test fun `conscript spends county grain and changes troops without raising fortification`() {
        val route = fixture.route()
        val actor = fixture.person(711, 1, route.startCity, userId = "42")
        val stock = CountyWarehouse(route.startCity, 0, Resources(grain = 1_000_000))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1,
                meta = city.meta + (CountyWarehouse.META_KEY to stock.toMetaValue())) else city
        })
        val handler = CityMilitaryHandler(world, ChangeRecorder())
        val before = world.getCityById(route.startCity)!!
        assertEquals(100, CityMilitaryState.read(before.meta).troops)
        val applied = assertIs<TurnOutcome.Applied>(handler.handle(MilitaryInput.CONSCRIPT,
            actor.id, "{}", "conscript-711", 42))
        val after = world.getCityById(route.startCity)!!
        assertEquals(950, after.population)
        assertEquals(150, CityMilitaryState.read(after.meta).troops)
        assertEquals(before.defence, after.defence)
        assertEquals(985_000L, CountyWarehouse.read(after.meta, after.id)!!.stock.grain)
        assertEquals(applied, handler.handle(MilitaryInput.CONSCRIPT, actor.id, "{}", "conscript-711", 42))
        assertEquals(150, CityMilitaryState.read(world.getCityById(after.id)!!.meta).troops)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `insufficient stock and lost county reject with no troops or household change`() {
        val route = fixture.route()
        val actor = fixture.person(712, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1) else city
        })
        val handler = CityMilitaryHandler(world, ChangeRecorder())
        val before = world.getCityById(route.startCity)!!
        assertEquals("WAREHOUSE_NOT_READY", assertIs<TurnOutcome.Rejected>(handler.handle(
            MilitaryInput.CONSCRIPT, actor.id, "{}", "conscript-712", 42)).code)
        assertEquals(before, world.getCityById(route.startCity))
        world.applyCityDirtyFree(before.copy(nationId = 2))
        assertEquals("FOREIGN_COUNTY", assertIs<TurnOutcome.Rejected>(handler.handle(
            MilitaryInput.TRAIN, actor.id, "{}", "train-712", 42)).code)
        assertEquals(0, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `autonomous lord uses the same city military handler for low training`() {
        val route = fixture.route()
        val actor = fixture.person(713, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, meta = city.meta +
                (CityMilitaryState.META_KEY to CityMilitaryState(30, 50, 100).toMetaValue())) else city
        })
        val selected = NpcCityMilitarySelector(DomesticContext()).select(world, actor.id,
            ReservedTurn("휴식", "{}", rowExists = false))
        assertEquals(MilitaryInput.TRAIN, selected.actionCode)
        assertIs<TurnOutcome.Applied>(CityMilitaryHandler(world, ChangeRecorder()).handle(
            selected.actionCode, actor.id, selected.argJson, null, null, npcSelected = true))
        assertEquals(40, CityMilitaryState.read(world.getCityById(route.startCity)!!.meta).training)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }
}
