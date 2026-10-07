package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.engine.turn.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.logic.content.TreasureSlot
import opensamguk.logic.items.BaseStatItemModule
import opensamguk.logic.items.ItemRegistry
import opensamguk.infra.seed.UnitProfilesJson
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.world.StrategicNodeRef

/** action.tradeEquipment: ordinary equipment delivery and legacy treasure denial. */
class DirectActionHandlerTest {
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
        val handler = DirectActionHandler(world, ChangeRecorder(), context())
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
        assertIs<TurnOutcome.Applied>(DirectActionHandler(world, ChangeRecorder(), context()).handle(
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
        val handler = DirectActionHandler(world, ChangeRecorder(), context())
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
        val handler = DirectActionHandler(world, ChangeRecorder(), context())
        assertEquals(DirectFailure.INVALID_INPUT.name,
            assertIs<TurnOutcome.Rejected>(handler.handle(DirectInput.TRANSPORT,
                actor.id, """{"targetCountyId":$targetId,"cargo":"GRAIN","amount":1001}""", "too-much", 42)).code)
        assertIs<TurnOutcome.Applied>(handler.handle(DirectInput.TRANSPORT, actor.id,
            """{"targetCountyId":$targetId,"cargo":"GRAIN","amount":1000}""", "transport-4041", 42))
        assertEquals(500, CountyWarehouse.read(world.getCityById(sourceId)!!.meta, sourceId)!!.stock.grain)
        assertEquals(1000, CountyWarehouse.read(world.getCityById(targetId)!!.meta, targetId)!!.stock.grain)
    }
    @Test fun `ordinary equipment buy consumes real price equips persists and affects the next engine read`() {
        val gear = ItemCatalogJson.CANON.equipment.first { it.slot == TreasureSlot.HORSE }
        val route = fixture.route()
        val actor = fixture.person(4051, 1, route.startCity, userId = "42").copy(gold = gear.purchaseCost,
            role = GeneralRole(items = GeneralItems("None", "None", "None", "None")))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, security = gear.requiredSecurity,
                meta = city.meta + stock(city.id)) else city
        })
        val recorder = ChangeRecorder()
        val pipeline = EngineGeneralActionPipelineBuilder(world, 200)
        val before = PerTurnOverlay.toLogicGeneral(actor)
        val initialStat = pipeline.pipelineFor(actor).onCalcStat(before, "leadership", 70.0)
        val handler = DirectActionHandler(world, recorder, context())
        val raw = """{"equipmentId":"${gear.id}","side":"BUY"}"""
        val applied = assertIs<TurnOutcome.Applied>(handler.handle(DirectInput.EQUIPMENT, actor.id, raw, "gear-buy", 42))
        val after = world.getGeneralById(actor.id)!!
        assertEquals(gear.sourceCode, after.role.items.horse)
        assertEquals(0, after.gold)
        assertEquals(gear.purchaseCost.toLong(), CountyWarehouse.read(world.getCityById(route.startCity)!!.meta, route.startCity)!!.stock.money)
        assertEquals(gear.sourceCode, context().projection(world).person(actor.id)!!.equipmentSlots!![gear.slot])
        assertEquals(gear.requiredSecurity, context().projection(world).county(route.startCity)!!.security)
        assertEquals(setOf(actor.id), recorder.dirtyGeneralIds())
        assertEquals(gear.sourceCode, recorder.generalPatches().single().columns["horse"])
        val equipped = PerTurnOverlay.toLogicGeneral(after)
        val item = assertIs<BaseStatItemModule>(ItemRegistry().resolve(gear.sourceCode))
        assertEquals(initialStat + item.statValue, pipeline.pipelineFor(after).onCalcStat(equipped, "leadership", 70.0))
        assertEquals(applied, handler.handle(DirectInput.EQUIPMENT, actor.id, raw, "gear-buy", 42))
        assertEquals(after, world.getGeneralById(actor.id))
        assertEquals(DirectFailure.ALREADY_PROCESSED.name, assertIs<TurnOutcome.Rejected>(handler.handle(
            DirectInput.EQUIPMENT, actor.id, raw, "second-buy", 42)).code)
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        val persisted = payload.updatedGenerals.single { it.id == actor.id }
        assertEquals(gear.sourceCode, persisted.horse)
        assertEquals(after.gold, persisted.gold)
        assertEquals(after.meta, persisted.meta)
        assertEquals(emptySet(), TreasureInventory.read(persisted.meta))
    }

    @Test fun `ordinary equipment sell credits same canonical price clears only its slot and removes effect`() {
        val gear = ItemCatalogJson.CANON.equipment.first { it.slot == TreasureSlot.WEAPON }
        val route = fixture.route()
        val actor = fixture.person(4061, 1, route.startCity, userId = "42").copy(gold = 0,
            role = GeneralRole(items = GeneralItems("None", gear.sourceCode, "None", "None")))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, security = gear.requiredSecurity,
                meta = city.meta + stock(city.id, money = gear.purchaseCost.toLong())) else city
        })
        val recorder = ChangeRecorder()
        val pipeline = EngineGeneralActionPipelineBuilder(world, 200)
        val item = assertIs<BaseStatItemModule>(ItemRegistry().resolve(gear.sourceCode))
        val beforeStat = pipeline.pipelineFor(actor).onCalcStat(PerTurnOverlay.toLogicGeneral(actor), "strength", 70.0)
        assertIs<TurnOutcome.Applied>(DirectActionHandler(world, recorder, context()).handle(DirectInput.EQUIPMENT,
            actor.id, """{"equipmentId":"${gear.id}","side":"SELL"}""", "gear-sell", 42))
        val after = world.getGeneralById(actor.id)!!
        assertEquals(gear.purchaseCost, after.gold)
        assertEquals(actor.role.items.copy(weapon = "None"), after.role.items)
        assertEquals(0L, CountyWarehouse.read(world.getCityById(route.startCity)!!.meta, route.startCity)!!.stock.money)
        assertEquals(beforeStat - item.statValue,
            pipeline.pipelineFor(after).onCalcStat(PerTurnOverlay.toLogicGeneral(after), "strength", 70.0))
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        assertEquals("None", payload.updatedGenerals.single().weapon)
        assertEquals(gear.purchaseCost, payload.updatedGenerals.single().gold)
    }

    @Test fun `execution rechecks queued equipment slot security resources and owner without partial writes`() {
        val gear = ItemCatalogJson.CANON.equipment.first()
        val route = fixture.route()
        val base = fixture.person(4071, 1, route.startCity, userId = "42").copy(gold = gear.purchaseCost,
            role = GeneralRole(items = GeneralItems("None", "None", "None", "None")))
        val cases = listOf(
            Triple(base.copy(role = base.role.copy(items = base.role.items.copy(horse = gear.sourceCode))), gear.requiredSecurity, "EQUIPMENT_SLOT_OCCUPIED"),
            Triple(base.copy(role = base.role.copy(items = base.role.items.copy(horse = null))), gear.requiredSecurity, "STATE_UNAVAILABLE"),
            Triple(base, gear.requiredSecurity - 1, "INSUFFICIENT_SECURITY"),
            Triple(base.copy(gold = gear.purchaseCost - 1), gear.requiredSecurity, "INSUFFICIENT_STOCK"),
            Triple(base.copy(userId = "99"), gear.requiredSecurity, "FORBIDDEN"),
        )
        for ((actor, security, code) in cases) {
            val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
                if (city.id == route.startCity) city.copy(nationId = 1, security = security,
                    meta = city.meta + stock(city.id)) else city
            })
            val recorder = ChangeRecorder()
            val originalCity = world.getCityById(route.startCity)
            assertEquals(code, assertIs<TurnOutcome.Rejected>(DirectActionHandler(world, recorder, context()).handle(
                DirectInput.EQUIPMENT, actor.id, """{"equipmentId":"${gear.id}","side":"BUY"}""", "queued-gear", 42)).code)
            assertEquals(actor, world.getGeneralById(actor.id))
            assertEquals(originalCity, world.getCityById(route.startCity))
            assertTrue(recorder.dirtyGeneralIds().isEmpty())
            assertTrue(recorder.dirtyCityIds().isEmpty())
        }
    }

    @Test fun `unwired consumable item variants never write equipment resources or result stamps`() {
        val route = fixture.route()
        val actor = fixture.person(4081, 1, route.startCity, userId = "42").copy(gold = 10000,
            role = GeneralRole(items = GeneralItems("None", "None", "None", "None")))
        val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
            if (city.id == route.startCity) city.copy(nationId = 1, security = 10000,
                meta = city.meta + stock(city.id, money = 10000)) else city
        })
        val recorder = ChangeRecorder()
        val handler = DirectActionHandler(world, recorder, context())
        val originalCity = world.getCityById(route.startCity)
        for (item in ItemCatalogJson.CANON.equipment.filter { it.consumable }) for (side in TradeSide.entries) {
            assertEquals("EQUIPMENT_UNAVAILABLE", assertIs<TurnOutcome.Rejected>(handler.handle(DirectInput.EQUIPMENT,
                actor.id, """{"equipmentId":"${item.id}","side":"${side.name}"}""", "unwired-item", 42)).code)
            assertEquals(actor, world.getGeneralById(actor.id))
            assertEquals(originalCity, world.getCityById(route.startCity))
            assertTrue(recorder.dirtyGeneralIds().isEmpty())
            assertTrue(recorder.dirtyCityIds().isEmpty())
        }
    }

    @Test fun `each supported equipment slot reaches its own flush column and existing stat consumer`() {
        val supported = TreasureSlot.entries.mapNotNull { slot -> ItemCatalogJson.CANON.equipment.firstOrNull {
            it.slot == slot && !it.consumable
        } }
        assertEquals(setOf(TreasureSlot.HORSE, TreasureSlot.WEAPON, TreasureSlot.BOOK), supported.map { it.slot }.toSet())
        val route = fixture.route()
        for (gear in supported) {
            val actor = fixture.person(4091, 1, route.startCity, userId = "42").copy(gold = gear.purchaseCost,
                role = GeneralRole(items = GeneralItems("None", "None", "None", "None")))
            val world = fixture.world(listOf(actor to route.start), cityChanges = { city ->
                if (city.id == route.startCity) city.copy(nationId = 1, security = gear.requiredSecurity,
                    meta = city.meta + stock(city.id)) else city
            })
            val recorder = ChangeRecorder()
            val pipeline = EngineGeneralActionPipelineBuilder(world, 200)
            val module = assertIs<BaseStatItemModule>(ItemRegistry().resolve(gear.sourceCode))
            val initial = pipeline.pipelineFor(actor).onCalcStat(PerTurnOverlay.toLogicGeneral(actor), module.statType, 70.0)
            assertIs<TurnOutcome.Applied>(DirectActionHandler(world, recorder, context()).handle(DirectInput.EQUIPMENT,
                actor.id, """{"equipmentId":"${gear.id}","side":"BUY"}""", "slot-buy", 42))
            val after = world.getGeneralById(actor.id)!!
            val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
            val read = payload.updatedGenerals.single()
            assertEquals(gear.sourceCode, context().projection(world).person(actor.id)!!.equipmentSlots!![gear.slot])
            assertEquals(mapOf("horse" to read.horse, "weapon" to read.weapon, "book" to read.book)[gear.slot.name.lowercase()], gear.sourceCode)
            assertEquals(initial + module.statValue, pipeline.pipelineFor(after).onCalcStat(read, module.statType, 70.0))
            assertEquals(3, listOf(after.role.items.horse, after.role.items.weapon, after.role.items.book, after.role.items.item).count { it == "None" })
        }
    }

}
