package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.domestic.DomesticDesign
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.input.InputCatalog
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn

class FieldHandlerTest {
    private val fixture = CampaignWorldFixture()
    private val design = DomesticDesign.CANON

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
        val handler = FieldHandler(world, recorder, DomesticContext(design = design))
        val before = world.getCityById(route.startCity)!!.agriculture
        val applied = assertIs<TurnOutcome.Applied>(handler.handle(FieldInput.FARM, actor.id, "{}", "farm-501", 42))
        val first = world.getCityById(route.startCity)!!.agriculture
        assertTrue(first > before)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
        assertEquals(1, world.getGeneralById(actor.id)!!.dedication)
        assertEquals(applied, handler.handle(FieldInput.FARM, actor.id, "{}", "farm-501", 42))
        assertEquals(first, world.getCityById(route.startCity)!!.agriculture)
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `fortification spends county stock and insufficient stock rejects without changes`() {
        val route = fixture.route()
        val actor = fixture.person(502, 1, route.startCity, userId = "42")
        val warehouse = CountyWarehouse(route.startCity, 0, Resources(money = 5_000, timber = 250))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1,
                meta = city.meta + (CountyWarehouse.META_KEY to warehouse.toMetaValue())) else city
        })
        val handler = FieldHandler(world, ChangeRecorder(), DomesticContext(design = design))
        val before = world.getCityById(route.startCity)!!.defence
        // Actor intelligence is 70: cost scales beyond the unscaled 5,000/250 stock.
        val rejected = assertIs<TurnOutcome.Rejected>(handler.handle(FieldInput.FORTIFY,
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
        val rejected = assertIs<TurnOutcome.Rejected>(FieldHandler(world, ChangeRecorder(),
            DomesticContext(design = design)).handle(FieldInput.FARM, actor.id, "{}", "farm-503", 42))
        assertEquals("FOREIGN_COUNTY", rejected.code)
        assertEquals(0, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `autonomous NPC chooses the same field input and handler from its own county`() {
        val route = fixture.route()
        val actor = fixture.person(504, 1, route.startCity, userId = null)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1) else city
        })
        val catalog = InputCatalog.load()
        val selected = NpcFieldSelector(DomesticContext(design = design), catalog)
            .select(world, actor.id, ReservedTurn("휴식", "{}", rowExists = false))
        assertEquals(FieldInput.FARM, selected.actionCode)
        assertIs<TurnOutcome.Applied>(FieldHandler(world, ChangeRecorder(), DomesticContext(design = design))
            .handle(selected.actionCode, actor.id, selected.argJson, null, null, npcSelected = true))
        assertEquals(10, world.getGeneralById(actor.id)!!.experience)
    }

    @Test fun `autonomous NPC preserves an active corps march instead of choosing county work`() {
        val route = fixture.route()
        val actor = fixture.person(505, 1, route.startCity, userId = null)
        val world = fixture.world(listOf(actor to route.start), bugoks = listOf(fixture.unit(506, actor.id, 100)),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 1) else city })
        fixture.deploy(world, ChangeRecorder(), actor.id, listOf(506), route.destination)
        val reserved = ReservedTurn("휴식", "{}", rowExists = false)
        assertEquals(reserved, NpcFieldSelector(DomesticContext(design = design))
            .select(world, actor.id, reserved))
    }
}
