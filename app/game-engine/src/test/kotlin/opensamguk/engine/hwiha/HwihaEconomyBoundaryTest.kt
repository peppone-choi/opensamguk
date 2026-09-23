package opensamguk.engine.hwiha

import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.retainer.RetainerMonthlyService
import opensamguk.engine.turn.*
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules

/** 순 경계 보급 재계산, 녹봉(창고망), 기존 가신 유지비 끔, 상사 — in-memory, real map. */
class HwihaEconomyBoundaryTest {
    private val fixture = HwihaCampaignWorldFixture()
    private val route = fixture.route()
    private val capital = route.startCity

    private fun warehouse(city: City, stock: HwihaResources) =
        city.copy(meta = city.meta + (HwihaCountyWarehouse.META_KEY to HwihaCountyWarehouse(city.id, 0, stock).toMetaValue()))

    private fun money(world: InMemoryTurnWorld, county: Int) =
        HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock.money

    /** Nation 1 owns every city except [enemyCounty]; its capital is the route's start county. */
    private fun realm(enemyCounty: Int? = null, capitalMoney: Long = 0, card: Retainer? = null,
        people: List<Pair<TurnGeneral, opensamguk.logic.world.StrategicNodeRef.LandProvince>> =
            listOf(fixture.person(1, 1, capital, userId = "42") to route.start,
                fixture.person(2, 1, capital, lord = false) to route.start),
        bugoks: List<Bugok> = emptyList()) = fixture.world(people, bugoks = bugoks,
        nations = listOf(Nation(1, "N1", "#111111", capitalCityId = capital, level = 1, chiefGeneralId = 1),
            Nation(2, "N2", "#222222", level = 1, capitalCityId = enemyCounty ?: 0)),
        retainers = listOfNotNull(card),
        cityChanges = { city ->
            val owner = if (city.id == enemyCounty) 2 else 1
            val owned = city.copy(nationId = owner, supplyState = 0)
            when (city.id) {
                capital -> warehouse(owned, HwihaResources(money = capitalMoney))
                enemyCounty -> warehouse(owned.copy(population = 50_000, agriculture = 1000), HwihaResources())
                else -> owned
            }
        })

    @Test fun `each phase recomputes supply flags from the capital and cuts a besieged county`() {
        val world = realm()
        val boundary = HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells)
        assertTrue(boundary.recomputeSupply(world, ChangeRecorder(), emptySet()) > 0)
        assertEquals(1, world.getCityById(capital)!!.supplyState, "the capital is supplied")
        val neighbour = fixture.bundle.cityConst.byId(capital)!!.path.keys.first()
        assertEquals(1, world.getCityById(neighbour)!!.supplyState, "a connected own city is supplied")
        boundary.recomputeSupply(world, ChangeRecorder(), setOf(neighbour))
        assertEquals(0, world.getCityById(neighbour)!!.supplyState, "encirclement cuts outside supply")
    }

    @Test fun `a county captured at the boundary is supplied in time for the new owner's income`() {
        val county = route.destinationCounty
        val world = realm(enemyCounty = county,
            people = listOf(fixture.person(1, 1, capital, userId = "42") to route.first),
            bugoks = listOf(fixture.unit(7, 1, 1000)))
        val recorder = ChangeRecorder()
        fixture.deploy(world, recorder, 1, listOf(7), route.destination)
        fixture.nextPhase(world)
        fixture.movement(world, recorder).onTurn(1, HwihaCampaignWorldFixture.NO_INPUT)
        val boundary = HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells)
        repeat(4) { fixture.nextPhase(world); boundary.run(world, recorder) }
        assertEquals(1, world.getCityById(county)!!.nationId)
        assertEquals(1, world.getCityById(county)!!.supplyState, "the captured county joined the captor's network")
        val state = world.getState()
        HwihaMonthlyCountyIncome(world, recorder).credit(state.currentYear, state.currentMonth)
        val stock = HwihaCountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock
        assertTrue(stock.grain > 0, "the new owner's county warehouse receives the month's income")
    }

    @Test fun `salary is paid from the card's network once a month and unpaid cards lose loyalty`() {
        val card = Retainer(4, 1, RetainerRules.ORIGIN_EXISTING, 2, "G2", RetainerRules.RELATION_LIEUTENANT, loyalty = 50)
        val world = realm(capitalMoney = 1000, card = card)
        HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).recomputeSupply(world, ChangeRecorder(), emptySet())
        val recorder = ChangeRecorder()
        val first = HwihaMonthlySalary(world, recorder).pay(200, 2)!!
        assertEquals(1, first.paid); assertEquals(700L, first.money, "cost 7 × 100")
        assertEquals(300L, money(world, capital)); assertEquals(50, world.getRetainerById(4)!!.loyalty)
        assertTrue(HwihaMonthlySalary(world, recorder).pay(200, 2)!!.alreadyStamped)
        assertEquals(300L, money(world, capital), "the same month is not paid twice")
        val unpaid = HwihaMonthlySalary(world, recorder).pay(200, 3)!!
        assertEquals(1, unpaid.unpaid); assertEquals(300L, money(world, capital), "no partial payment")
        assertEquals(45, world.getRetainerById(4)!!.loyalty)
    }

    @Test fun `legacy retainer upkeep and unit pay are off in HWIHA but provisions and drift remain`() {
        val card = Retainer(4, 1, RetainerRules.ORIGIN_RECRUITED, null, "무명", RetainerRules.RELATION_STAFF, loyalty = 50)
        val world = realm(card = card, bugoks = listOf(fixture.unit(7, 1, 100, provisions = 500)))
        RetainerMonthlyService().settle(world, ChangeRecorder())
        assertEquals(50 + RetainerRules.LOYALTY_IDLE, world.getRetainerById(4)!!.loyalty, "no unpaid-upkeep loss")
        assertEquals(0, world.getGeneralById(1)!!.gold, "no legacy gold paid")
        val unit = world.getBugokById(7)!!
        assertEquals(50, unit.morale, "no unpaid-pay morale loss"); assertEquals(400, unit.provisions, "provisions still consumed")
    }

    @Test fun `reward is a queued court decision paid from the warehouse raising loyalty and one bond event a month`() {
        val card = Retainer(4, 1, RetainerRules.ORIGIN_EXISTING, 2, "G2", RetainerRules.RELATION_LIEUTENANT, loyalty = 50)
        val world = realm(capitalMoney = 2000, card = card)
        HwihaPhaseBoundary(fixture.topology, fixture.metrics, fixture.cells).recomputeSupply(world, ChangeRecorder(), emptySet())
        val recorder = ChangeRecorder()
        val court = HwihaCourtHandler(world, recorder)
        val queued = court.handle(TurnDaemonCommand.HwihaCourtInput("reward-1", 1, 42, "court.reward", """{"retainerId":4,"money":500}"""))
        assertTrue(queued.ok, "${queued.code} ${queued.reason}")
        assertEquals(2000L, money(world, capital), "nothing is paid before the issuer's turn")
        court.onIssuerTurn(1)
        assertEquals(1500L, money(world, capital)); assertEquals(55, world.getRetainerById(4)!!.loyalty)
        assertTrue(court.takeExecutions().single().result.ok)
        assertEquals(1, (world.getGeneralById(2)!!.meta[HwihaRenownEvents.TALLY_META_KEY] as Map<*, *>)["bondEvent"])
        assertEquals(HwihaRewardExecutor.Failure.INSUFFICIENT_STOCK, HwihaRewardExecutor(world, recorder).reward(RewardRequest(1, 4, 5000)))
        assertNull(HwihaRewardExecutor(world, recorder).reward(RewardRequest(1, 4, 100)))
        assertEquals(1, (world.getGeneralById(2)!!.meta[HwihaRenownEvents.TALLY_META_KEY] as Map<*, *>)["bondEvent"], "one bond event a month")
        assertEquals(HwihaRewardExecutor.Failure.CARD_UNAVAILABLE, HwihaRewardExecutor(world, recorder).reward(RewardRequest(2, 4, 100)))
    }
}
