package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — che_단련(CheDanryeon) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 개인 커맨드. 병종 숙련도를 향상시킨다. 2-draw RNG.
 *
 * Draw stream:
 *   DRAW1: choiceUsingWeightPair → ["normal", 2] (multiplier=2)
 *   DRAW2: choiceUsingWeight → "strength_exp"
 *
 * 검증 항목:
 *   - seedString byte-equal
 *   - DRBG cursor: 2 draws consumed
 *   - logLines/broadcastLines byte-match
 *   - after-delta: gold, rice, experience, strength_exp
 */
class CheDanryeonGoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `che_단련 golden byte-matches the PHP action log, 2-draw RNG, and post-state`() {
        val command = "che_단련"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        for (c in f.cases) {
            // P2GoldenSupport.generalFrom은 crewTypeId를 설정하지 않음.
            // PHP fixture의 장수는 crew=5000(병)이므로 crewTypeId=1100(병)으로 수동 설정.
            val baseGeneral = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
            val actor = baseGeneral.copy(crewTypeId = 1100)

            val city = P2GoldenSupport.cityFrom(c.cityId, c.before.city)
            val nation = P2GoldenSupport.nationFrom(c)

            val draft = GeneralActionDraft(actor, city, nation)

            val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
            assertEquals(c.seedString, seed, "[$command/${c.name}] seedString byte-match")
            val drbg = LiteHashDrbg(seed)
            val rng = RandUtil(drbg)

            val stateBefore = drbg.peekStateIdx()
            val bufferBefore = drbg.peekBufferIdx()

            val ctx = GeneralActionResolveContext(
                draft = draft,
                rng = rng,
                env = P2GoldenSupport.envOf(c),
                month = c.env.month,
                date = P2GoldenSupport.dateOf(c),
                args = c.arg ?: emptyMap(),
                generalName = P2GoldenSupport.nameOf(c.generalId),
            )

            def.resolve(ctx)

            // draw-for-draw: golden draw_count == 2
            val ref = RandUtil(LiteHashDrbg(seed))
            ref.choiceUsingWeightPair(listOf(
                Pair("success" to 3, 0.34),
                Pair("normal" to 2, 0.33),
                Pair("fail" to 1, 0.33),
            )) // DRAW1
            ref.choiceUsingWeight(linkedMapOf(
                "leadership_exp" to 42.0,
                "strength_exp" to 73.0,
                "intel_exp" to 24.0,
            )) // DRAW2

            assertEquals(ref.nextFloat1(), ctx.rng.nextFloat1(), 0.0,
                "[$command/${c.name}] resolve가 정확히 2회 draw 소비 (no extra/missing)")

            // 로그 byte-match
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // after-delta
            val ag = c.after.general
            val g = draft.general
            assertEquals(ag.int("gold"), g.gold, "[$command/${c.name}] general.gold")
            assertEquals(ag.int("rice"), g.rice, "[$command/${c.name}] general.rice")
            assertEquals(ag.int("experience"), phpRound(g.experience), "[$command/${c.name}] general.experience")
            assertEquals(ag.int("strength_exp"), metaInt(g.meta, "strength_exp"), "[$command/${c.name}] meta.strength_exp")
            assertEquals(ag.int("leadership_exp"), metaInt(g.meta, "leadership_exp"), "[$command/${c.name}] meta.leadership_exp")
            assertEquals(ag.int("intel_exp"), metaInt(g.meta, "intel_exp"), "[$command/${c.name}] meta.intel_exp")
            assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "[$command/${c.name}] meta.explevel")
        }
    }
}
