package opensamguk.logic.battle.realtime.economy

import kotlin.test.*

class ReserveTest {
    @Test fun `unknown acquisition rules keep reserve zero`() {
        val s = seed()
        for (p in listOf(EconomyParams(), ready.copy(reserveAcquisitionEnabled = null), ready.copy(reserveBatchSize = null)))
            rejected(s, s.acquireReserve("r", "A", "popA", "debit", p), EconomyCode.NOT_DECIDED)
        assertEquals(0L, s.reserve("A")); assertEquals(100L, s.source("popA")!!.available)
    }
    @Test fun `reserve acquisition records source once and refuses duplicate debit IDs`() {
        val s = seed().acquireReserve("r", "A", "popA", "debit", ready).ok()
        assertEquals(10L, s.reserve("A")); assertEquals(90L, s.source("popA")!!.available)
        assertEquals(EconomyCode.ALREADY_APPLIED, s.acquireReserve("r", "A", "popA", "debit", ready).code)
        rejected(s, s.acquireReserve("r2", "A", "popA", "debit", ready), EconomyCode.DUPLICATE_ID)
        rejected(s, s.acquireReserve("r3", "B", "popA", "debit3", ready), EconomyCode.OWNER_CHANGED)
    }
    @Test fun `zero home population cannot create a direct recruit or reserve`() {
        val s = seed(people = 0).housing().production()
        rejected(s, s.acquireReserve("r", "A", "popA", "debit", ready), EconomyCode.INSUFFICIENT_POPULATION)
        rejected(s, s.advanceProduction("t", "o", true, ready), EconomyCode.INSUFFICIENT_POPULATION)
    }
}
