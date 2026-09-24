package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.HwihaDomesticDesign
import opensamguk.logic.input.HwihaFieldInput
import opensamguk.logic.input.HwihaInputCatalog
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn

class HwihaFieldHandlerTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val design = HwihaDomesticDesign.parse(
        javaClass.classLoader.getResource(HwihaDomesticDesign.RESOURCE)!!.readText()
            .replace("\"status\": \"PROPOSED\"", "\"status\": \"CONFIRMED\""))

    @Test fun `pinned administrative counties have a unique land province per county`() {
        val grouped = fixture.bundle.projection.administrativeCountyIds.groupBy {
            fixture.bundle.projection.bindingsByCityId[it]?.landProvinceId
        }
        assertTrue(null !in grouped.keys)
        assertTrue(grouped.values.all { it.size == 1 }, grouped.filterValues { it.size > 1 }.toString())
    }

    @Test fun `one direct farm applies to current county once and grows its actor`() {
        val route = fixture.route()
        val actor = fixture.person(501, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1) else city
        })
        val recorder = ChangeRecorder()
        val handler = HwihaFieldHandler(world, recorder, HwihaDomesticContext(design = design))
        val before = world.getCityById(route.startCity)!!.agriculture
        val applied = assertIs<HwihaTurnOutcome.Applied>(handler.handle(HwihaFieldInput.FARM, actor.id, "{}", "farm-501", 42))
        val first = world.getCityById(route.startCity)!!.agriculture
        assertTrue(first > before)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
        assertEquals(1, world.getGeneralById(actor.id)!!.dedication)
        assertEquals(applied, handler.handle(HwihaFieldInput.FARM, actor.id, "{}", "farm-501", 42))
        assertEquals(first, world.getCityById(route.startCity)!!.agriculture)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `fortification spends county stock and insufficient stock rejects without changes`() {
        val route = fixture.route()
        val actor = fixture.person(502, 1, route.startCity, userId = "42")
        val warehouse = HwihaCountyWarehouse(route.startCity, 0, HwihaResources(money = 10_000, timber = 500))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1,
                meta = city.meta + (HwihaCountyWarehouse.META_KEY to warehouse.toMetaValue())) else city
        })
        val handler = HwihaFieldHandler(world, ChangeRecorder(), HwihaDomesticContext(design = design))
        val before = world.getCityById(route.startCity)!!.defence
        // Actor intelligence is 70: cost scales beyond the unscaled 10,000/500 stock.
        val rejected = assertIs<HwihaTurnOutcome.Rejected>(handler.handle(HwihaFieldInput.FORTIFY,
            actor.id, "{}", "fort-502", 42))
        assertEquals("INSUFFICIENT_STOCK", rejected.code)
        assertEquals(before, world.getCityById(route.startCity)!!.defence)
        assertEquals(0, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `changed ownership rejects at execution with no cost`() {
        val route = fixture.route()
        val actor = fixture.person(503, 1, route.startCity, userId = "42")
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 2) else city
        })
        val rejected = assertIs<HwihaTurnOutcome.Rejected>(HwihaFieldHandler(world, ChangeRecorder(),
            HwihaDomesticContext(design = design)).handle(HwihaFieldInput.FARM, actor.id, "{}", "farm-503", 42))
        assertEquals("FOREIGN_COUNTY", rejected.code)
        assertEquals(0, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `autonomous NPC chooses the same field input and handler from its own county`() {
        val route = fixture.route()
        val actor = fixture.person(504, 1, route.startCity, userId = null)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1) else city
        })
        val rawCatalog = javaClass.classLoader.getResource("command-catalog/hwiha-input-catalog.json")!!.readText()
        val catalog = HwihaInputCatalog.parse(rawCatalog.replace("\"deliveryState\": \"PLANNED\"",
            "\"deliveryState\": \"HANDLER_READY\""))
        val selected = HwihaNpcFieldSelector(HwihaDomesticContext(design = design), catalog)
            .select(world, actor.id, ReservedTurn("휴식", "{}", rowExists = false))
        assertEquals(HwihaFieldInput.FARM, selected.actionCode)
        assertIs<HwihaTurnOutcome.Applied>(HwihaFieldHandler(world, ChangeRecorder(), HwihaDomesticContext(design = design))
            .handle(selected.actionCode, actor.id, selected.argJson, null, null, npcSelected = true))
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }
}
