package opensamguk.logic.diplomacy

import opensamguk.logic.domain.Diplomacy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests for [DiplomacyMonthProcessor] — faithful Kotlin port of TypeScript processDiplomacyMonth().
 *
 * PHP grand truth: diplomacy rows are directional (me→you); term counts down monthly;
 * war casualties extend term; both-direction term≤1 ends war; declaration expiry → war;
 * non-aggression expiry → trade.
 */
class DiplomacyMonthProcessorTest {

    @Test
    fun `term decrements for all entries`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.TRADE, term = 5),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.TRADE, term = 3),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        assertEquals(4, result[0].term)
        assertEquals(2, result[1].term)
    }

    @Test
    fun `term does not go below zero`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.TRADE, term = 0),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        assertEquals(0, result[0].term)
    }

    @Test
    fun `non-aggression expiry transitions to trade`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.NON_AGGRESSION, term = 1),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.NON_AGGRESSION, term = 1),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        // term decrements to 0, then expiry kicks in → TRADE
        assertEquals(DiplomacyState.TRADE, result[0].state)
        assertEquals(DiplomacyState.TRADE, result[1].state)
    }

    @Test
    fun `declaration expiry transitions to war with default term`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.DECLARATION, term = 1),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.DECLARATION, term = 1),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        assertEquals(DiplomacyState.WAR, result[0].state)
        assertEquals(DiplomacyConst.DEFAULT_WAR_TERM, result[0].term)
        assertEquals(DiplomacyState.WAR, result[1].state)
        assertEquals(DiplomacyConst.DEFAULT_WAR_TERM, result[1].term)
    }

    @Test
    fun `war casualties increase term`() {
        // dead=500, genCount=5 → termIncrease = 500/100/5 = 1
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.WAR, term = 6, dead = 500),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.WAR, term = 6, dead = 0),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, mapOf(1 to 5, 2 to 3))
        // Forward: term increases by 1, then decrements → 6+1-1 = 6
        assertEquals(6, result[0].term)
        // dead should be reduced: 500 - 1*100*5 = 0
        assertEquals(0, result[0].dead)
        // Reverse: no dead, just decrements → 6-1 = 5
        assertEquals(5, result[1].term)
    }

    @Test
    fun `war term clamped at max`() {
        // dead=2000, genCount=1 → termIncrease = 2000/100/1 = 20, but clamped to MAX_WAR_TERM=13
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.WAR, term = 10, dead = 2000),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.WAR, term = 10, dead = 0),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, mapOf(1 to 1))
        // 10 + 20 = 30 → clamped to 13, then -1 → 12
        assertEquals(12, result[0].term)
    }

    @Test
    fun `both war terms le 1 ends war and transitions to trade`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.WAR, term = 1, dead = 0),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.WAR, term = 1, dead = 0),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        // Both directions: term ≤ 1 → TRADE, term = 0
        assertEquals(DiplomacyState.TRADE, result[0].state)
        assertEquals(0, result[0].term)
        assertEquals(DiplomacyState.TRADE, result[1].state)
        assertEquals(0, result[1].term)
    }

    @Test
    fun `single direction war term le 1 does not end war`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.WAR, term = 1, dead = 0),
            Diplomacy(me = 2, you = 1, state = DiplomacyState.WAR, term = 3, dead = 0),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        // Only forward has term ≤ 1, reverse does not → war continues
        assertEquals(DiplomacyState.WAR, result[0].state)
        assertEquals(DiplomacyState.WAR, result[1].state)
    }

    @Test
    fun `non-war dead is reset to zero`() {
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.TRADE, term = 5, dead = 100),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        assertEquals(0, result[0].dead)
    }

    @Test
    fun `war dead is preserved after term increase calculation`() {
        // dead=550, genCount=5 → termIncrease = 550/100/5 = 1, remaining dead = 550 - 500 = 50
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.WAR, term = 6, dead = 550),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, mapOf(1 to 5))
        assertEquals(50, result[0].dead)
    }

    @Test
    fun `missing general count defaults to 1`() {
        // Nation 1 not in generalCounts map → defaults to 1
        val diplomacy = listOf(
            Diplomacy(me = 1, you = 2, state = DiplomacyState.WAR, term = 6, dead = 100),
        )
        val result = DiplomacyMonthProcessor.process(diplomacy, emptyMap())
        // termIncrease = 100/100/1 = 1, term = 6+1-1 = 6
        assertEquals(6, result[0].term)
    }
}
