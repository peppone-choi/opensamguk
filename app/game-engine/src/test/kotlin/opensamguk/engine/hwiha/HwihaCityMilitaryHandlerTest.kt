package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.HwihaCityMilitaryState
import opensamguk.logic.input.HwihaMilitaryInput
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn

class HwihaCityMilitaryHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()

    @Test fun `conscript spends county grain and changes troops without raising fortification`() {
        val route = fixture.route()
        val actor = fixture.person(711, 1, route.startCity, userId = "42")
        val stock = HwihaCountyWarehouse(route.startCity, 0, HwihaResources(grain = 1_000_000))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1,
                meta = city.meta + (HwihaCountyWarehouse.META_KEY to stock.toMetaValue())) else city
        })
        val handler = HwihaCityMilitaryHandler(world, ChangeRecorder())
        val before = world.getCityById(route.startCity)!!
        assertEquals(100, HwihaCityMilitaryState.read(before.meta).troops)
        val applied = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaMilitaryInput.CONSCRIPT,
            actor.id, "{}", "conscript-711", 42))
        val after = world.getCityById(route.startCity)!!
        assertEquals(950, after.population)
        assertEquals(150, HwihaCityMilitaryState.read(after.meta).troops)
        assertEquals(before.defence, after.defence)
        assertEquals(985_000L, HwihaCountyWarehouse.read(after.meta, after.id)!!.stock.grain)
        assertEquals(applied, handler.handle(HwihaMilitaryInput.CONSCRIPT, actor.id, "{}", "conscript-711", 42))
        assertEquals(150, HwihaCityMilitaryState.read(world.getCityById(after.id)!!.meta).troops)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `insufficient stock and lost county reject with no troops or household change`() {
        val route = fixture.route()
        val actor = fixture.person(712, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1) else city
        })
        val handler = HwihaCityMilitaryHandler(world, ChangeRecorder())
        val before = world.getCityById(route.startCity)!!
        assertEquals("WAREHOUSE_NOT_READY", assertIs<HwihaTurnOutcome.Rejected>(handler.handle(
            HwihaMilitaryInput.CONSCRIPT, actor.id, "{}", "conscript-712", 42)).code)
        assertEquals(before, world.getCityById(route.startCity))
        world.applyCityDirtyFree(before.copy(nationId = 2))
        assertEquals("FOREIGN_COUNTY", assertIs<HwihaTurnOutcome.Rejected>(handler.handle(
            HwihaMilitaryInput.TRAIN, actor.id, "{}", "train-712", 42)).code)
        assertEquals(0, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `autonomous lord uses the same city military handler for low training`() {
        val route = fixture.route()
        val actor = fixture.person(713, 1, route.startCity)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, meta = city.meta +
                (HwihaCityMilitaryState.META_KEY to HwihaCityMilitaryState(30, 50, 100).toMetaValue())) else city
        })
        val selected = HwihaNpcCityMilitarySelector(HwihaDomesticContext()).select(world, actor.id,
            ReservedTurn("휴식", "{}", rowExists = false))
        assertEquals(HwihaMilitaryInput.TRAIN, selected.actionCode)
        assertIs<HwihaTurnOutcome.Applied>(HwihaCityMilitaryHandler(world, ChangeRecorder()).handle(
            selected.actionCode, actor.id, selected.argJson, null, null, npcSelected = true))
        assertEquals(40, HwihaCityMilitaryState.read(world.getCityById(route.startCity)!!.meta).training)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }
}
