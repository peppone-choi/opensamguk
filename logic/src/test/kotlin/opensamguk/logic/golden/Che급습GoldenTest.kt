package opensamguk.logic.golden

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA F4-C3-CHIEF — che_급습(CheGeupseup) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 실제 Kotlin 리졸버를 [CommandRegistry]로 해석해 구동하고(REGISTRY RESOLUTION 필수),
 * golden/p2/che_급습-fixtures.json(hiddenSeed a2db167c.., acting gid 152)를 바이트/draw 오라클로 삼는다.
 *
 * 검증 항목(게이트 레시피):
 *  - 6-토큰 시드 문자열이 골든 seedString과 byte-equal(rngFor GUARD).
 *  - RNG draw 스트림: che_급습은 deterministic(draw_count=0) → LiteHashDRBG 커서(stateIdx/bufferIdx)가
 *    resolve 전/후 불변임을 확인(어떤 draw든 커서를 전진시키므로 0-draw 오라클).
 *  - acting logLines + broadcastLines byte-match.
 *  - general after-delta(experience/dedication, phpRound half-away).
 *
 * che_급습 run()(che_급습.php:125-200): 급습 발동 로그 1줄 + exp/ded +5 + nation strategic_cmd_limit=9 +
 * diplomacy term -3(양방향). 아국/적국 장수 개별 PLAIN 로그는 다른 ActionLogger로 flush되는 엔진-스코프
 * 부수효과라 actor scope에 나타나지 않으며 pushGlobalActionLog도 호출하지 않음 → broadcastLines=[].
 */
class Che급습GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `che_급습 golden byte-matches the PHP action log, zero-draw RNG, and post-state`() {
        val command = "che_급습"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        for (c in f.cases) {
            // --- draft: actor general + city + nation (전략 사령 — strategic_cmd_limit 적재) ---
            val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000,
                tech = P2GoldenSupport.techOf(c.before.general.int("nation")))
            val draft = GeneralActionDraft(
                P2GoldenSupport.generalFrom(c.generalId, c.before.general),
                P2GoldenSupport.cityFrom(c.cityId, c.before.city),
                nation,
            )

            // --- RNG: 6-토큰 시드를 직접 재구성해 DRBG 핸들을 잡는다(0-draw 커서 비교용). ---
            val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
            assertEquals(c.seedString, seed, "[$command/${c.name}] seedString must byte-equal the golden PHP oracle")
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

            // --- draw-for-draw: golden draw_count == 0 → DRBG 커서 불변(어떤 draw든 커서 전진) ---
            assertEquals(stateBefore, drbg.peekStateIdx(),
                "[$command/${c.name}] DRBG stateIdx must be unchanged (golden draw_count=0)")
            assertEquals(bufferBefore, drbg.peekBufferIdx(),
                "[$command/${c.name}] DRBG bufferIdx must be unchanged (golden draw_count=0)")

            // --- 로그 byte-match ---
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // --- general after-delta(phpRound half-away) ---
            val ag = c.after.general
            val g = draft.general
            assertEquals(ag.int("experience"), phpRound(g.experience), "[$command/${c.name}] general.experience")
            assertEquals(ag.int("dedication"), phpRound(g.dedication), "[$command/${c.name}] general.dedication")
            assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "[$command/${c.name}] meta.explevel")
            assertEquals(ag.int("gold"), g.gold, "[$command/${c.name}] general.gold")
        }
    }
}
