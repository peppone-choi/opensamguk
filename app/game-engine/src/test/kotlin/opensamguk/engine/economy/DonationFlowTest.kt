package opensamguk.engine.economy

import kotlin.test.*
import opensamguk.engine.campaign.*
import opensamguk.engine.turn.*
import opensamguk.logic.economy.*
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules
import opensamguk.logic.renown.RenownRules
import opensamguk.logic.war.CampaignBalance

class DonationFlowTest {
    private val fixture = CampaignWorldFixture()
    private val route = fixture.route()
    private fun world() = fixture.world(listOf(
        fixture.person(1, 2, route.startCity, userId = "42").copy(gold = 1000, rice = 100000) to route.start,
        fixture.person(10, 1, route.startCity, userId = "43") to route.start,
        fixture.person(11, 1, route.startCity, lord = false) to route.start),
        nations = listOf(Nation(1, "현 소유국", "#111111", capitalCityId = route.startCity, gold = 9, rice = 8),
            Nation(2, "무영토 기부국", "#222222", gold = 7, rice = 6)),
        bugoks = listOf(fixture.unit(7, 10, 100, provisions = 0)),
        retainers = listOf(Retainer(4, 10, RetainerRules.ORIGIN_EXISTING, 11, "G11", RetainerRules.RELATION_LIEUTENANT, loyalty = 50)),
        cityChanges = { city -> if (city.id != route.startCity) city.copy(nationId = 0) else city.copy(nationId = 1,
            supplyState = 1, meta = city.meta + (CountyWarehouse.META_KEY to
                CountyWarehouse(city.id, 0, Resources()).toMetaValue())) })
    private fun stock(world: InMemoryTurnWorld) = CountyWarehouse.read(world.getCityById(route.startCity)!!.meta, route.startCity)!!
    private fun donate(world: InMemoryTurnWorld, recorder: ChangeRecorder, resource: String, amount: Int, id: String) =
        TransferHandler(world, recorder, DomesticContext()).handle(TransferInput.DONATE, 1,
            """{"resource":"$resource","amount":$amount}""", id, 42)

    @Test fun `action.donate donated money funds actual salary and retries cannot duplicate either transfer or salary`() {
        val world = world(); val recorder = ChangeRecorder()
        val nationBefore = world.listNations()
        val cost = RenownRules.personCost(70, 70, 70, 70, 70).toLong() * CampaignBalance.SALARY_MONEY_PER_RENOWN_COST
        val first = assertIs<TurnOutcome.Applied>(donate(world, recorder, "MONEY", 1000, "money"))
        assertEquals(0, world.getGeneralById(1)!!.gold)
        assertEquals(1000L, stock(world).stock.money)
        assertEquals(1L, stock(world).revision)
        assertEquals(nationBefore, world.listNations(), "no mirrored national treasury credit")
        assertEquals(first, donate(world, recorder, "MONEY", 1000, "money"))
        assertEquals(1L, stock(world).revision)
        assertEquals(TransferFailure.ALREADY_PROCESSED.name,
            assertIs<TurnOutcome.Rejected>(donate(world, recorder, "MONEY", 1, "other")).code)
        assertEquals(cost, MonthlySalary(world, recorder).pay(200, 2)!!.money)
        assertEquals(1000L - cost, stock(world).stock.money)
        assertTrue(MonthlySalary(world, recorder).pay(200, 2)!!.alreadyStamped)
        assertEquals(1000L - cost, stock(world).stock.money)
    }
    @Test fun `donated grain is consumed by real resupply and deploy rations`() {
        val world = world(); val recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(donate(world, recorder, "GRAIN", 100000, "grain"))
        assertEquals(0, world.getGeneralById(1)!!.rice)
        val target = 100 * CampaignBalance.UNIT_RESUPPLY_TARGET_MONTHS
        assertEquals(1, UnitResupply(world, recorder).resupply(200, 2))
        assertEquals(target, world.getBugokById(7)!!.provisions)
        val afterResupply = 100000L - target.toLong() * CampaignBalance.GRAIN_PER_PROVISION
        assertEquals(afterResupply, stock(world).stock.grain)
        assertEquals(0, UnitResupply(world, recorder).resupply(200, 2))
        val corps = DeployedCorps("rations", 10, 10, null, 1, listOf(7), Phase(200, 2, 1))
        val loaded = CorpsRations(world, recorder, fixture.topology, fixture.metrics).load(corps)
        val expected = 100L * CampaignBalance.DEPLOY_LOAD_MONTHS - target
        assertEquals(expected, loaded)
        assertEquals(afterResupply - loaded * CampaignBalance.GRAIN_PER_PROVISION, stock(world).stock.grain)
        assertEquals(0L, CorpsRations(world, recorder, fixture.topology, fixture.metrics).load(corps))
    }
    @Test fun `isolated donation cannot draw salary from another supplied county`() {
        val world = world(); val recorder = ChangeRecorder()
        val local = world.getCityById(route.startCity)!!
        world.applyCityDirtyFree(local.copy(supplyState = 0))
        val other = world.getCityById(route.destinationCounty)!!
        world.applyCityDirtyFree(other.copy(nationId = 1, supplyState = 1, meta = other.meta +
            (CountyWarehouse.META_KEY to CountyWarehouse(other.id, 0, Resources(money = 10000)).toMetaValue())))
        assertIs<TurnOutcome.Applied>(donate(world, recorder, "MONEY", 100, "isolated"))
        assertEquals(listOf(local.id), WarehouseNetwork(world, recorder).countiesFor(1, local.id))
        val salary = MonthlySalary(world, recorder).pay(200, 2)!!
        assertEquals(0, salary.paid)
        assertEquals(1, salary.unpaid)
        assertEquals(100L, stock(world).stock.money)
        assertEquals(10000L, CountyWarehouse.read(world.getCityById(other.id)!!.meta, other.id)!!.stock.money)
        assertEquals(900, world.getGeneralById(1)!!.gold)
    }
    @Test fun `execution ownership and landless recipient preserve ledgers`() {
        val world = world(); val recorder = ChangeRecorder()
        val before = world.getGeneralById(1)!!
        world.applyGeneralDirtyFree(before.copy(userId = "99"))
        assertEquals("FORBIDDEN", assertIs<TurnOutcome.Rejected>(donate(world, recorder, "MONEY", 10, "deny")).code)
        assertEquals(0L, stock(world).revision)
        assertEquals(1000, world.getGeneralById(1)!!.gold)
        val network = WarehouseNetwork(world, recorder)
        assertTrue(network.countiesFor(2, route.startCity).isEmpty(), "landless recipient has no invented network")
        assertFalse(network.payMoney(2, emptyList(), 1))
        assertEquals(0L, stock(world).stock.money)
    }
}
