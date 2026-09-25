package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.infra.seed.UnitProfilesJson
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef

class LegacyDirectHandlerTest {
    private val fixture = CampaignWorldFixture()
    private fun context() = DomesticContext(cityConst = fixture.bundle.cityConst)
    private fun stock(id: Int, money: Long = 0, grain: Long = 0) = mapOf(
        CountyWarehouse.META_KEY to CountyWarehouse(id, 0,
            Resources(money = money, grain = grain)).toMetaValue())

    @Test fun `conversion changes owned unit type and loses ten training`() {
        val route = fixture.route()
        val actor = fixture.person(4011, 1, route.startCity, userId = "42")
        val unit = fixture.unit(71, actor.id, 1000)
        val nextType = UnitProfilesJson.loadDefault().profiles.first { it.crewTypeId != unit.crewTypeId }.crewTypeId
        val world = fixture.world(listOf(actor to route.start), bugoks = listOf(unit),
            cityChanges = { city -> if (city.id == route.startCity) city.copy(nationId = 1) else city })
        val handler = LegacyDirectHandler(world, ChangeRecorder(), context())
        val json = """{"bugokId":${unit.id},"crewTypeId":$nextType}"""
        val first = assertIs<TurnOutcome.Applied>(handler.handle(DirectInput.CONVERT,
            actor.id, json, "convert-4011", 42))
        assertEquals(nextType, world.getBugokById(unit.id)!!.crewTypeId)
        assertEquals(40, world.getBugokById(unit.id)!!.training)
        assertEquals(first, handler.handle(DirectInput.CONVERT, actor.id, json, "convert-4011", 42))
    }

    @Test fun `grain trade exchanges exact actor and county stocks`() {
        val route = fixture.route()
        val actor = fixture.person(4021, 1, route.startCity, userId = "42").copy(gold = 100)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, meta = city.meta + stock(city.id, grain = 300)) else city
        })
        assertIs<TurnOutcome.Applied>(LegacyDirectHandler(world, ChangeRecorder(), context()).handle(
            DirectInput.GRAIN, actor.id, """{"side":"BUY","amount":1}""", "grain-4021", 42))
        assertEquals(0, world.getGeneralById(actor.id)!!.gold)
        assertEquals(300, world.getGeneralById(actor.id)!!.rice)
        val after = CountyWarehouse.read(world.getCityById(route.startCity)!!.meta, route.startCity)!!.stock
        assertEquals(100, after.money)
        assertEquals(0, after.grain)
    }

    @Test fun `treasure trade remains unavailable until card effects are delivered`() {
        val route = fixture.route()
        val card = ItemCatalogJson.CANON.treasures.first { it.issuedCopies == 1 && it.purchaseCost > 0 }
        val actor = fixture.person(4031, 1, route.startCity, userId = "42").copy(gold = card.purchaseCost)
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, meta = city.meta + stock(city.id)) else city
        })
        val handler = LegacyDirectHandler(world, ChangeRecorder(), context())
        val rejected = assertIs<TurnOutcome.Rejected>(handler.handle(DirectInput.EQUIPMENT, actor.id,
            """{"treasureId":${card.sourceRowIndex},"side":"BUY"}""", "buy-4031", 42))
        assertEquals(InputRejection.NOT_DELIVERED.name, rejected.code)
        assertEquals(emptySet(), TreasureInventory.read(world.getGeneralById(actor.id)!!.meta))
        assertEquals(card.purchaseCost, world.getGeneralById(actor.id)!!.gold)
    }

    @Test fun `transport moves at most one thousand between adjacent allied county warehouses`() {
        val admin = fixture.bundle.projection.administrativeCountyIds
        val sourceId = admin.sorted().first { fixture.bundle.cityConst.byId(it)!!.path.keys.any { target -> target in admin } }
        val targetId = fixture.bundle.cityConst.byId(sourceId)!!.path.keys.first { it in admin }
        val province = checkNotNull(fixture.bundle.projection.bindingsByCityId[sourceId]!!.landProvinceId)
        val actor = fixture.person(4041, 1, sourceId, userId = "42")
        val world = fixture.world(listOf(actor to StrategicNodeRef.LandProvince(province)), cityChanges = { city -> when (city.id) {
            sourceId -> city.copy(nationId = 1, meta = city.meta + stock(city.id, grain = 1500))
            targetId -> city.copy(nationId = 1, meta = city.meta + stock(city.id))
            else -> city
        } })
        val handler = LegacyDirectHandler(world, ChangeRecorder(), context())
        assertEquals(DirectFailure.INVALID_INPUT.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(DirectInput.TRANSPORT,
                actor.id, """{"targetCountyId":$targetId,"cargo":"GRAIN","amount":1001}""", "too-much", 42)).code)
        assertIs<TurnOutcome.Applied>(handler.handle(DirectInput.TRANSPORT, actor.id,
            """{"targetCountyId":$targetId,"cargo":"GRAIN","amount":1000}""", "transport-4041", 42))
        assertEquals(500, CountyWarehouse.read(world.getCityById(sourceId)!!.meta, sourceId)!!.stock.grain)
        assertEquals(1000, CountyWarehouse.read(world.getCityById(targetId)!!.meta, targetId)!!.stock.grain)
    }
}
