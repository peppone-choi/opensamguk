package opensamguk.logic.actions.nation

import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.domain.LastTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Port-faithful test for the NationCommand base (NationCommand.php:8-59 + BaseCommand getPreReqTurn /
 * the per-command `5 * (getPreReqTurn()+1)` exp/ded magnitude shared by 감축/증축/천도/국호/국기/무작위수도이전).
 *
 * Pins:
 *  - the 3-arg ctor seeds `resultTurn` from `lastTurn` (NationCommand.php:13-14 `$lastTurn->duplicate()`).
 *  - the next_execute KV key (`next_execute_{actionName}`, NationCommand.php:30-34).
 *  - `setNextAvailable` math = joinYearMonth(year,month)+postReqTurn-preReqTurn, ONLY when postReqTurn
 *    is truthy (NationCommand.php:45-57); postReqTurn==0 → null (dead for the P2 8-command set).
 *  - EXP/DED magnitude `5*(preReqTurn+1)` (감축/증축→30, 천도 variable, 국호/국기→5).
 */
class NationCommandBaseTest {

    /** A minimal concrete NationCommand exposing the base behavior for the test. */
    private class FakeNation(
        override val name: String,
        private val preReq: Int,
        private val postReq: Int,
    ) : NationCommand() {
        override val key: String get() = "che_$name"
        override fun getPreReqTurn(): Int = preReq
        override fun getPostReqTurn(): Int = postReq
        override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()
        override fun resolve(context: GeneralActionResolveContext) { /* not under test here */ }
    }

    @Test
    fun `ctor seeds resultTurn from lastTurn (duplicate)`() {
        val cmd = FakeNation("감축", preReq = 5, postReq = 0)
        val last = LastTurn("감축", emptyMap(), term = 3, seq = 7)
        val state = cmd.newState(last)
        assertEquals(last, state.resultTurn, "resultTurn seeded from lastTurn")
        assertEquals(last, state.lastTurn, "lastTurn retained")
    }

    @Test
    fun `next_execute KV key is next_execute_actionName`() {
        assertEquals("next_execute_감축", FakeNation("감축", 5, 0).nextExecuteKey())
        assertEquals("next_execute_천도", FakeNation("천도", 8, 0).nextExecuteKey())
    }

    @Test
    fun `exp_ded magnitude is 5 times preReqTurn plus 1`() {
        // 감축/증축 preReqTurn=5 → 30
        assertEquals(30, FakeNation("감축", 5, 0).expDedMagnitude())
        assertEquals(30, FakeNation("증축", 5, 0).expDedMagnitude())
        // 국호/국기 preReqTurn=0 → 5 (NOT +30; TS hardcodes +30, PHP is +5 — research Unit 5)
        assertEquals(5, FakeNation("국호변경", 0, 0).expDedMagnitude())
        assertEquals(5, FakeNation("국기변경", 0, 0).expDedMagnitude())
        // 무작위수도이전 preReqTurn=1 → 10
        assertEquals(10, FakeNation("무작위 수도 이전", 1, 0).expDedMagnitude())
        // 천도 variable: distance 4 → preReqTurn 8 → 45
        assertEquals(45, FakeNation("천도", 8, 0).expDedMagnitude())
    }

    @Test
    fun `setNextAvailable math only fires when postReqTurn truthy`() {
        // joinYearMonth(190, 3) = 190*12 + 3 - 1 = 2282
        // postReqTurn=0 → null (the whole P2 8-command set: dead KV)
        assertNull(FakeNation("감축", 5, 0).computeNextAvailable(190, 3))
        // postReqTurn truthy → joinYearMonth + postReqTurn - preReqTurn
        assertEquals(2282 + 4 - 2, FakeNation("가상", preReq = 2, postReq = 4).computeNextAvailable(190, 3))
    }
}
