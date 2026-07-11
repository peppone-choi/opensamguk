package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.GeneralRankIncrement
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `che_탈취 success path mutates resource carriers, consumes sabotage item, and records firenum`() {
        val def = registry.resolve("che_탈취")
        val actor = General(88, 1, 1, 60, 255, 60, 0, 0.0, 0.0, 1, 1000, 1000, item = "che_계략_향낭")
        val draft = GeneralActionDraft(
            actor,
            City(1, 1, 8, 0, 1, 0, 1, 1, 0, 100.0),
            Nation(1, 2, 1, gold = 1000, rice = 1000),
        )
        draft.destCity = City(
            id = 2,
            nationId = 2,
            level = 8,
            commerce = 8000,
            commerceMax = 10000,
            agriculture = 8000,
            agricultureMax = 10000,
            supplyState = 1,
            frontState = 0,
            trust = 100.0,
            security = 0,
            securityMax = 10000,
        )
        draft.destNation = Nation(2, 2, 2, gold = 10_000, rice = 10_000)
        val ctx = successContext(def, draft)

        assertEquals(32, draft.destCity?.state)
        assertTrue((draft.destNation?.gold ?: 10_000) < 10_000)
        assertTrue((draft.destNation?.rice ?: 10_000) < 10_000)
        assertTrue((draft.nation?.gold ?: 1000) > 1000)
        assertTrue((draft.nation?.rice ?: 1000) > 1000)
        assertEquals("None", draft.general.item)
        assertTrue(ctx.logs().any { it.contains("탈취가 성공했습니다") })
        assertTrue(ctx.logs().any { it.contains("향낭(계략)") })
        assertEquals(listOf(GeneralRankIncrement(88, "firenum", 1)), draft.rankIncrements)
    }

    private fun successContext(def: opensamguk.logic.actions.GeneralActionDefinition, draft: GeneralActionDraft): GeneralActionResolveContext {
        val seed = successSeed("che-talchwi-success")
        val ctx = GeneralActionResolveContext(
            draft = draft,
            rng = RandUtil(LiteHashDrbg(seed)),
            env = WorldEnv(181, 181, 20),
            month = 1,
            date = "04:06",
            args = linkedMapOf("destCityID" to 2),
            candidateGenerals = emptyList(),
            cityDistance = 1,
        )
        def.resolve(ctx)
        assertTrue(ctx.globalActionLogs().isNotEmpty())
        return ctx
    }

    private fun successSeed(prefix: String): String {
        for (i in 0 until 128) {
            val seed = "$prefix-$i"
            if (RandUtil(LiteHashDrbg(seed)).nextBool(0.5)) return seed
        }
        error("no deterministic success seed found")
    }
}
