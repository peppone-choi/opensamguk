package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_선동(CheSeondong) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 계략(sabotage) 커맨드. 적 dest 도시의 치안/민심을 선동으로 낮춘다.
 * 캡처된 골든은 실패 분기(draw_count=3).
 *
 * Draw stream (fail branch):
 *   DRAW1: nextBool(prob≈0.2333) → false
 *   DRAW2: nextRangeInt(1,100) → 16  (experience)
 *   DRAW3: nextRangeInt(1,70)  → 25  (dedication)
 */
class CheSeondongGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `che_선동 golden byte-matches the PHP action log, 3-draw RNG, and post-state`() {
        SabotageGoldenSupport.runFailBranch(
            command = "che_선동",
            dir = "p2",
            registry = registry,
            failProb = 0.23326816663782046,
            statExpKey = "leadership_exp",
        )
    }
}
