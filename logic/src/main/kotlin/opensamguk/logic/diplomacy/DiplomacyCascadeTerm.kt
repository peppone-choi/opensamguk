package opensamguk.logic.diplomacy

/**
 * Cascade-diplomacy term application — frozen historical PHP formulas (ADR-LITE-042; not current
 * product authority) for strategic commands that cannot
 * emit absolute terms without a live pre-row read.
 *
 * ## Encoding (logic resolvers → engine apply)
 * Strategic commands stage a [opensamguk.logic.domain.Diplomacy] cascade row. Most commands write
 * **absolute** `(state, term)`. Two C3 strategic commands encode a **row-relative** formula that the
 * engine must expand against the pre-row (PHP `UPDATE diplomacy SET term=…`):
 *
 * 1. **급습** (`che_급습.php:192-194`): `term = term - 3`, state unchanged.
 *    Resolver encodes `term = -3` (negative = relative delta). Apply: `newTerm = pre.term + delta.term`.
 *
 * 2. **이호경식** (`che_이호경식.php:187-190`): `term = IF(state=0, 3, term+3)`, `state = 1`.
 *    Resolver encodes `state=DECLARATION, term=3` (the IF constant). Apply only when the pre-row is
 *    war(0) or declaration(1) — the only states [AllowDiplomacyBetweenStatus([0,1])] permits.
 *    Absolute `term=3` on other states (or non-declaration target states) is left as absolute.
 *
 * Absolute paths (선전포고 term=24, 불가침수락 computed term, 종전/파기 term=0) are pass-through.
 */
object DiplomacyCascadeTerm {

    data class Applied(val state: Int, val term: Int)

    /**
     * Expand a cascade-encoded `(deltaState, deltaTerm)` against the pre-row `(preState, preTerm)`.
     *
     * @return the absolute `(state, term)` to write (PHP UPDATE result).
     */
    fun apply(
        preState: Int,
        preTerm: Int,
        deltaState: Int,
        deltaTerm: Int,
    ): Applied {
        // 급습: negative term = relative delta; state is not updated by PHP.
        if (deltaTerm < 0) {
            return Applied(state = preState, term = preTerm + deltaTerm)
        }
        // 이호경식: IF(state=0, 3, term+3) + state→DECLARATION, encoded as term=3/state=1.
        if (deltaState == DiplomacyState.DECLARATION &&
            deltaTerm == 3 &&
            preState in setOf(DiplomacyState.WAR, DiplomacyState.DECLARATION)
        ) {
            val newTerm = if (preState == DiplomacyState.WAR) 3 else preTerm + 3
            return Applied(state = DiplomacyState.DECLARATION, term = newTerm)
        }
        // Absolute replacement (수락/선전포고/파기/종전 …).
        return Applied(state = deltaState, term = deltaTerm)
    }
}
