package opensamguk.engine.economy

import kotlin.test.*
import opensamguk.engine.campaign.CampaignWorldFixture
import opensamguk.engine.campaign.RewardExecutor
import opensamguk.engine.campaign.WarehouseNetwork
import opensamguk.engine.campaign.WarehouseSettlement
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.Retainer
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.economy.WarehouseFundingScope
import opensamguk.logic.input.RewardMoneyLimit
import opensamguk.logic.input.RewardRequest
import opensamguk.logic.retainer.RetainerRules

class RewardFundingSnapshotTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()
    private val county = route.startCity
    private fun world() = fixture.world(
        listOf(fixture.person(1, 1, county, userId = "42") to route.start,
            fixture.person(2, 1, county, lord = false) to route.start),
        nations = listOf(Nation(1, "N1", "#111111", capitalCityId = county)),
        retainers = listOf(Retainer(4, 1, RetainerRules.ORIGIN_EXISTING, 2, "G2",
            RetainerRules.RELATION_LIEUTENANT, loyalty = 95)),
        cityChanges = { city -> if (city.id != county) city else city.copy(nationId = 1, supplyState = 1,
            meta = mapOf(CountyWarehouse.META_KEY to CountyWarehouse(county, 0, Resources(money = 500)).toMetaValue())) })

    @Test fun `shared funding selection matches the executor adapter without consuming any stock`() {
        val world = world()
        val before = world.listCities()
        val selected = WarehouseFundingScope.countiesFor(1, county, world::getCityById,
            { world.getNationById(1)?.capitalCityId }, world::listCities, { it.id }, { it.nationId },
            { it.supplyState != 0 }, { id -> id in world.administrativeCountyIds &&
                CountyWarehouse.read(world.getCityById(id)!!.meta, id) != null })
        assertEquals(listOf(county), selected)
        assertEquals(selected, WarehouseNetwork(world, ChangeRecorder()).countiesFor(1, county))
        assertEquals(before, world.listCities())
    }

    @Test fun `a covered estimate does not stop execution rejecting intervening debits or loyalty changes`() {
        val world = world()
        val recorder = ChangeRecorder()
        val network = WarehouseNetwork(world, recorder)
        assertEquals(500L, network.moneyIn(network.countiesFor(1, county)))
        assertEquals(500L, RewardMoneyLimit.maximumFor(world.getRetainerById(4)!!.loyalty))
        assertEquals(WarehouseSettlement.Result.APPLIED,
            WarehouseSettlement(world, recorder).settle(county, 1, 0, Resources(money = 500)))
        assertEquals(RewardExecutor.Failure.INSUFFICIENT_STOCK,
            RewardExecutor(world, recorder).reward(RewardRequest(1, 4, 100)))
        val changed = world()
        changed.updateRetainer(changed.getRetainerById(4)!!.copy(loyalty = 100))
        assertEquals(RewardExecutor.Failure.REWARD_OVER_CAP,
            RewardExecutor(changed, ChangeRecorder()).reward(RewardRequest(1, 4, 500)))
    }
}
