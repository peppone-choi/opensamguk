package opensamguk.logic.golden

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test

/**
 * AREA GATE-RUNTIME — che_탈취(CheTalchwi) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 계략(sabotage) 커맨드. 적 dest 도시에서 금/쌀을 탈취한다.
 * 캡처된 골든은 실패 분기(draw_count=3).
 *
 * Draw stream (fail branch):
 *   DRAW1: nextBool(prob≈0.2567) → false
 *   DRAW2: nextRangeInt(1,100) → 15  (experience)
 *   DRAW3: nextRangeInt(1,70)  → 64  (dedication)
 */
class CheTalchwiGoldenTest {

    private val registry = CommandRegistry(GeneralActionPipeline())

    @Test
    fun `che_탈취 golden byte-matches the PHP action log, 3-draw RNG, and post-state`() {
        SabotageGoldenSupport.runFailBranch(
            command = "che_탈취",
            dir = "p2",
            registry = registry,
            failProb = 0.2566666666666667,
            statExpKey = "strength_exp",
        )
    }
}
