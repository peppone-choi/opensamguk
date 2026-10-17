package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

class ProductionQueueTest {
    @Test fun `partial production cancel refunds original stock and population once losing time`() {
        val before = seed().housing(); var s = before.production().advanceProduction("t1", "o", true, ready).ok().advanceProduction("t2", "o", true, ready).ok()
        assertEquals(99L, s.source("popA")!!.available); assertEquals(96L, s.store("a")!!.stock.grain)
        s = s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready).ok()
        assertEquals(before.store("a")!!.stock, s.store("a")!!.stock); assertEquals(100L, s.source("popA")!!.available)
        assertEquals(0L, s.order("o")!!.progress); assertEquals(ProductionStatus.CANCELLED, s.order("o")!!.status)
        assertEquals(EconomyCode.ALREADY_APPLIED, s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready).code)
        rejected(s, s.cancelProduction("c2", "A", "o", EconomyMode.REALTIME, ready), EconomyCode.ALREADY_CANCELLED)
    }
    @Test fun `completed item cannot refund resources and individual units cost no turns`() {
        var s = seed().housing().production(target = 3, mode = EconomyMode.GENERAL_TURN)
        assertEquals(3L, s.totalTurns())
        repeat(9) { s = s.advanceProduction("t$it", "o", true, ready).also { r -> assertEquals(0L, r.turnsSpent) }.ok() }
        assertEquals(3L, s.housingUsed("A")); assertEquals(97L, s.source("popA")!!.available); assertEquals(3L, s.totalTurns())
        assertEquals(ProductionStatus.COMPLETED, s.order("o")!!.status)
        rejected(s, s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready), EconomyCode.ALREADY_COMPLETED)
    }
    @Test fun `blocked production consumes stock and reserve without double debiting home population`() {
        var s = seed().housing().acquireReserve("reserve", "A", "popA", "strategic-debit", ready).ok().production()
        assertEquals(90L, s.source("popA")!!.available)
        s = s.advanceProduction("t1", "o", false, ready).ok().advanceProduction("t2", "o", false, ready).ok()
        assertEquals(9L, s.reserve("A")); assertEquals(90L, s.source("popA")!!.available)
        assertEquals("strategic-debit", s.order("o")!!.populationDebit!!.debitRecordId)
        s = s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready).ok()
        assertEquals(10L, s.reserve("A")); assertEquals(90L, s.source("popA")!!.available); assertEquals(100L, s.store("a")!!.stock.grain)
    }
    @Test fun `insufficient stock and zero reserve stop with no partial change`() {
        var s = seed().housing().production()
        rejected(s, s.advanceProduction("p", "o", false, ready), EconomyCode.INSUFFICIENT_RESERVE)
        s = s.transfer("drain", "A", "a", "b", ResourceBundle(grain = 100, stone = 100), ready).ok()
        rejected(s, s.advanceProduction("p", "o", false, ready), EconomyCode.INSUFFICIENT_STOCK)
        s = s.advanceProduction("reconnect", "o", true, ready).let { assertEquals(EconomyCode.INSUFFICIENT_STOCK, it.code); it.ledger }
        assertEquals(100L, s.source("popA")!!.available)
    }
    @Test fun `reconnection uses home population again and never credits external resources`() {
        var s = seed().housing().acquireReserve("r", "A", "popA", "d", ready).ok().production(target = 2)
        repeat(3) { s = s.advanceProduction("blocked$it", "o", false, ready).ok() }
        assertEquals(9L, s.reserve("A")); assertEquals(90L, s.source("popA")!!.available)
        repeat(3) { s = s.advanceProduction("connected$it", "o", true, ready).ok() }
        assertEquals(9L, s.reserve("A")); assertEquals(89L, s.source("popA")!!.available); assertEquals(88L, s.store("a")!!.stock.grain)
    }
    @Test fun `owner changed population source makes cancellation undecided without any refund`() {
        val s = seed().housing().production().advanceProduction("p", "o", true, ready).ok().observeSourceOwner("conquest", "popA", "B").ok()
        rejected(s, s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready), EconomyCode.NOT_DECIDED)
        assertEquals(98L, s.store("a")!!.stock.grain)
    }
    @Test fun `production and cancellation seams cannot invent turn costs or formulas`() {
        val s = seed().housing()
        rejected(s, s.enqueue("e", "A", "o", "p", "a", "popA", recipe, 1, EconomyMode.GENERAL_TURN, ready.copy(productionTurnCost = null)), EconomyCode.NOT_DECIDED)
        val q = s.production()
        rejected(q, q.cancelProduction("c", "A", "o", EconomyMode.GENERAL_TURN, ready.copy(cancelTurnCost = null)), EconomyCode.NOT_DECIDED)
        for (r in listOf(recipe.copy(durationTicks = null), recipe.copy(perTickCost = null), recipe.copy(status = ParameterStatus.PROPOSED))) {
            val n = s.production(r = r)
            rejected(n, n.advanceProduction("t", "o", true, ready), EconomyCode.NOT_DECIDED)
        }
        rejected(q, q.advanceProduction("p", "o", null, ready), EconomyCode.NOT_DECIDED)
    }
    @Test fun `queue order and target changes consume instruction turns only`() {
        var s = seed().housing().production().production("second")
        rejected(s, s.advanceProduction("early", "second", true, ready), EconomyCode.QUEUE_BLOCKED)
        s = s.changeProduction("change", "A", "o", recipe, 2, EconomyMode.GENERAL_TURN, ready).ok()
        assertEquals(3L, s.totalTurns())
        s = s.advanceProduction("p", "o", true, ready).ok()
        rejected(s, s.changeProduction("change2", "A", "o", recipe, 3, EconomyMode.GENERAL_TURN, ready), EconomyCode.NOT_DECIDED)
    }
    @Test fun `owner ledger spans own warehouses and cancel refunds exact originating stores`() {
        var s = seed().housing().production().transfer("split", "A", "a", "aa", ResourceBundle(grain = 100), ready).ok()
        val a = s.store("a")!!.stock; val aa = s.store("aa")!!.stock
        s = s.advanceProduction("p", "o", true, ready).ok()
        assertEquals(2, s.order("o")!!.debits.size)
        assertEquals(98L, s.store("aa")!!.stock.grain)
        s = s.cancelProduction("c", "A", "o", EconomyMode.REALTIME, ready).ok()
        assertEquals(a, s.store("a")!!.stock); assertEquals(aa, s.store("aa")!!.stock)
    }
    @Test fun `A depleted does not stop B production`() {
        val s = seed().housing().housing("B", "hb").production().production("bo", owner = "B")
            .transfer("t", "A", "a", "b", ResourceBundle(grain = 100, stone = 100), ready).ok()
        rejected(s, s.advanceProduction("a", "o", true, ready), EconomyCode.INSUFFICIENT_STOCK)
        assertEquals(1L, s.advanceProduction("b", "bo", true, ready).ok().order("bo")!!.progress)
    }
}
