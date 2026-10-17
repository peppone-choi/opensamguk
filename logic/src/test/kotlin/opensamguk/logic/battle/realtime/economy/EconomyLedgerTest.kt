package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

internal val ready = EconomyParams(
    status = ParameterStatus.CONFIRMED, carrierCapacity = 1000, gatherAmount = 5,
    farmWorkers = 1, farmTicks = 2, collectionKinds = setOf("warehouse"), collectionRadius = 1, depositTicks = 1,
    dropRetainedNumerator = 1, dropRetainedDenominator = 1, transferEnabled = true,
    reserveAcquisitionEnabled = true, reserveBatchSize = 10, housingPerBuilding = 20,
    reserveHousingDuringProduction = false, productionTurnCost = 3, cancelTurnCost = 2,
    routeTurnCost = 4, routeAmount = ResourceBundle(grain = 5), routeLegTicks = 2,
)
internal val recipe = ProductionRecipe(HousingKind.SOLDIER, ResourceBundle(grain = 2, stone = 1), 3, ParameterStatus.CONFIRMED)
internal fun seed(stock: Long = 100, people: Long = 100) = EconomyLedger(listOf(
    EconomyStore("a", "A", ResourceBundle(grain = stock, stone = stock)),
    EconomyStore("aa", "A", ResourceBundle.ZERO), EconomyStore("b", "B", ResourceBundle(grain = stock, stone = stock)),
), listOf(PopulationSource("popA", "A", people), PopulationSource("popB", "B", people)))
internal fun EconomyResult.ok(): EconomyLedger { assertEquals(EconomyCode.APPLIED, code); return ledger }
internal fun EconomyLedger.worker(owner: String = "A", unit: String = "worker"): EconomyLedger =
    admitUnit("unit-$unit", owner, unit, HousingKind.WORKER).ok().registerCarrier("carrier-$unit", owner, unit, CarrierKind.WORKER).ok()
internal fun EconomyLedger.transport(owner: String = "A", unit: String = "cart"): EconomyLedger = registerCarrier("carrier-$unit", owner, unit, CarrierKind.TRANSPORT).ok()
internal fun EconomyLedger.housing(owner: String = "A", id: String = "house", params: EconomyParams = ready) = addHousing("house-$id", owner, id, params).ok()
internal fun EconomyLedger.production(order: String = "o", target: Long = 1, owner: String = "A", params: EconomyParams = ready,
    mode: EconomyMode = EconomyMode.REALTIME, r: ProductionRecipe = recipe): EconomyLedger =
    enqueue("enqueue-$order", owner, order, "producer-$owner", if (owner == "A") "a" else "b", "pop$owner", r, target, mode, params).ok()
internal fun rejected(before: EconomyLedger, result: EconomyResult, expected: EconomyCode) {
    assertEquals(expected, result.code); assertSame(before, result.ledger); assertEquals(0L, result.turnsSpent)
}

class EconomyLedgerTest {
    @Test fun `explicit transfer conserves all six resources and is idempotent`() {
        val amount = ResourceBundle(1, 2, 3, 4, 5, 6)
        val start = EconomyLedger(listOf(EconomyStore("a", "A", amount), EconomyStore("b", "B", ResourceBundle.ZERO)))
        val next = start.transfer("tx", "A", "a", "b", amount, ready).ok()
        assertEquals(ResourceBundle.ZERO, next.stock("A")); assertEquals(amount, next.stock("B"))
        assertEquals(EconomyCode.ALREADY_APPLIED, next.transfer("tx", "A", "a", "b", amount, ready).code)
        rejected(next, next.transfer("tx", "A", "a", "b", ResourceBundle(grain = 1), ready), EconomyCode.COMMAND_CONFLICT)
        assertEquals(amount, start.stock("A"))
    }
    @Test fun `transfer policy null or proposed never moves stock`() {
        val s = seed()
        for (p in listOf(EconomyParams(), ready.copy(transferEnabled = null), ready.copy(status = ParameterStatus.PROPOSED)))
            rejected(s, s.transfer("t", "A", "a", "b", ResourceBundle(grain = 1), p), EconomyCode.NOT_DECIDED)
    }
    @Test fun `unauthorized and insufficient transfer is atomic`() {
        val s = seed()
        rejected(s, s.transfer("t", "B", "a", "b", ResourceBundle(grain = 1), ready), EconomyCode.NOT_OWNER)
        rejected(s, s.transfer("t", "A", "a", "b", ResourceBundle(grain = 101), ready), EconomyCode.INSUFFICIENT_STOCK)
    }
    @Test fun `overflow is a rejection not a partial debit`() {
        val s = EconomyLedger(listOf(EconomyStore("a", "A", ResourceBundle(grain = 1)), EconomyStore("b", "B", ResourceBundle(grain = Long.MAX_VALUE - 1))))
        // Total seed still fits; node cargo can subsequently overflow one destination only.
        val w = s.worker().registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.GRAIN, 5, ready).ok().gather("g", "A", "n", "worker", ready).ok()
        rejected(w, w.depositCargo("d", "A", "worker", "b", 0, 1, ready), EconomyCode.NOT_OWNER)
        val own = EconomyLedger(listOf(EconomyStore("a", "A", ResourceBundle(grain = Long.MAX_VALUE))))
            .worker().registerNode("n", "n", NodeKind.DEPOSIT, ResourceKind.GRAIN, 5, ready).ok().gather("g", "A", "n", "worker", ready).ok()
        rejected(own, own.depositCargo("d", "A", "worker", "a", 0, 1, ready), EconomyCode.INVALID_INPUT)
    }
    @Test fun `command fingerprint and snapshot ignore map and set insertion order`() {
        val stores = listOf(EconomyStore("b", "B", ResourceBundle(stone = 2)), EconomyStore("a", "A", ResourceBundle(grain = 5)))
        val a = EconomyLedger(stores); val b = EconomyLedger(stores.reversed())
        assertEquals(a.sha256(), b.sha256())
        val p = ready.copy(collectionKinds = linkedSetOf("warehouse", "depot"))
        val q = ready.copy(collectionKinds = linkedSetOf("depot", "warehouse"))
        val one = a.transfer("t", "A", "a", "b", ResourceBundle(grain = 1), p).ok()
        val two = b.transfer("t", "A", "a", "b", ResourceBundle(grain = 1), q).ok()
        assertEquals(one.sha256(), two.sha256())
        assertEquals(EconomyCode.ALREADY_APPLIED, one.transfer("t", "A", "a", "b", ResourceBundle(grain = 1), q).code)
    }
    @Test fun `invalid identities cannot create a receipt`() {
        val s = seed(); rejected(s, s.transfer("", "A", "a", "b", ResourceBundle(grain = 1), ready), EconomyCode.INVALID_INPUT)
        assertFailsWith<IllegalArgumentException> { ResourceBundle(grain = -1) }
        assertFailsWith<IllegalArgumentException> { EconomyLedger(listOf(EconomyStore("x", "A", ResourceBundle.ZERO), EconomyStore("x", "B", ResourceBundle.ZERO))) }
    }
}
