package opensamguk.logic.diplomacy

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * PHP grand-truth formulas for cascade-encoded diplomacy terms.
 *
 *  - 급습: `term = term - 3` (che_급습.php:192-194)
 *  - 이호경식: `term = IF(state=0, 3, term+3)`, state→1 (che_이호경식.php:187-190)
 *  - absolute paths (선전포고/수락) pass through unchanged
 */
class DiplomacyCascadeTermTest {

    @Test
    fun `급습 negative term is relative subtract — state preserved`() {
        // pre declaration term=15 → after term=12, state stays 1
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.DECLARATION,
            preTerm = 15,
            deltaState = DiplomacyState.DECLARATION,
            deltaTerm = -3,
        )
        assertEquals(DiplomacyState.DECLARATION, applied.state)
        assertEquals(12, applied.term)
    }

    @Test
    fun `급습 relative subtract does not clamp below zero (PHP SQL has no floor)`() {
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.DECLARATION,
            preTerm = 2,
            deltaState = DiplomacyState.DECLARATION,
            deltaTerm = -3,
        )
        assertEquals(DiplomacyState.DECLARATION, applied.state)
        assertEquals(-1, applied.term)
    }

    @Test
    fun `이호경식 from war sets term to 3 and state to declaration`() {
        // PHP IF(state=0, 3, term+3) → 3 when war
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.WAR,
            preTerm = 6,
            deltaState = DiplomacyState.DECLARATION,
            deltaTerm = 3,
        )
        assertEquals(DiplomacyState.DECLARATION, applied.state)
        assertEquals(3, applied.term)
    }

    @Test
    fun `이호경식 from declaration adds 3 to existing term`() {
        // pre declaration term=12 → 15
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.DECLARATION,
            preTerm = 12,
            deltaState = DiplomacyState.DECLARATION,
            deltaTerm = 3,
        )
        assertEquals(DiplomacyState.DECLARATION, applied.state)
        assertEquals(15, applied.term)
    }

    @Test
    fun `absolute 선전포고 term 24 is pass-through`() {
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.TRADE,
            preTerm = 0,
            deltaState = DiplomacyState.DECLARATION,
            deltaTerm = DiplomacyConst.DEFAULT_DECLARE_WAR_TERM,
        )
        assertEquals(DiplomacyState.DECLARATION, applied.state)
        assertEquals(24, applied.term)
    }

    @Test
    fun `absolute 종전 term 0 is pass-through`() {
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.WAR,
            preTerm = 5,
            deltaState = DiplomacyState.TRADE,
            deltaTerm = 0,
        )
        assertEquals(DiplomacyState.TRADE, applied.state)
        assertEquals(0, applied.term)
    }

    @Test
    fun `absolute non-aggression term is pass-through even when term equals 3`() {
        // term=3 with NON_AGGRESSION must NOT be mistaken for 이호경식 encoding
        val applied = DiplomacyCascadeTerm.apply(
            preState = DiplomacyState.TRADE,
            preTerm = 0,
            deltaState = DiplomacyState.NON_AGGRESSION,
            deltaTerm = 3,
        )
        assertEquals(DiplomacyState.NON_AGGRESSION, applied.state)
        assertEquals(3, applied.term)
    }
}
