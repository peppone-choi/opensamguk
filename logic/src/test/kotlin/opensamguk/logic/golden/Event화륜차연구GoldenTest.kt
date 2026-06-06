package opensamguk.logic.golden

import kotlinx.serialization.json.Json
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
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA F4-C3-CHIEF — event_화륜차연구(EventHwaryunchaYeongu) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 실제 Kotlin 리졸버를 [CommandRegistry]로 해석해 구동하고(REGISTRY RESOLUTION 필수),
 * golden/p2/event_화륜차연구-fixtures.json(hiddenSeed cbc88849.., acting gid 152 ⓝ하진, nation 1 후한 chief)을
 * 바이트/draw 오라클로 삼는다. 픽스처 sha256 = git fe92703 캡처 blob과 byte-identical(실제 PHP 캡처, scenario_1010).
 *
 * event_화륜차연구.php run() — RNG 미사용(deterministic, draw_count=0). 효과(PHP run() 순서):
 *   exp/ded += 5*(23+1)=120 → nation gold -= 100000, rice -= 100000, aux[can_화륜차사용]=1 → actor action 로그
 *   "<M>화륜차 연구</> 완료" → general-history/national-history(엔진-스코프) → inheritance active_action+1(P6 seam).
 *
 * 검증 항목(C3 게이트 레시피):
 *  - 6-토큰 시드 문자열이 골든 seedString과 byte-equal(rngFor GUARD).
 *  - RNG draw 스트림: deterministic(draw_count=0) → LiteHashDRBG 커서(stateIdx/bufferIdx)가 resolve 전/후 불변.
 *  - actor logLines(1) + broadcastLines([]) byte-match. (general-history/national-history는 별도 로그 스코프라
 *    actor scope 미노출 — C3 패밀리(CheChotohwa 등)와 동일. 골든이 별도 surface로 캡처하되 actor scope는 1줄.)
 *  - general after-delta(experience/dedication, phpRound half-away).
 *  - nation after-delta(gold/rice 차감 + aux[can_화륜차사용]=1, 기존 aux 키/순서 보존) — nationBefore/nationAfter
 *    는 골든 JSON에서 직접 파싱(P2GoldenSupport 공유 DTO에는 nation aux surface가 없음).
 */
class Event화륜차연구GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `event_화륜차연구 golden byte-matches the PHP action log, zero-draw RNG, and post-state`() {
        val command = "event_화륜차연구"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        // nationBefore/nationAfter(+aux) surface는 공유 DTO 밖 — 골든 JSON에서 케이스별로 직접 파싱한다.
        val rawCases = Json.parseToJsonElement(
            P2GoldenSupport::class.java.classLoader
                .getResourceAsStream("golden/p2/$command-fixtures.json")!!
                .readBytes().toString(Charsets.UTF_8),
        ).jsonObject["cases"]!!.jsonArray.map { it.jsonObject }

        for ((idx, c) in f.cases.withIndex()) {
            val rawCase = rawCases[idx]
            val nationBefore = rawCase["nationBefore"]!!.jsonObject
            val nationAfter = rawCase["nationAfter"]!!.jsonObject
            val auxBefore = nationBefore["aux"]!!.jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.int as Any? }
                .toMap(LinkedHashMap())
            val auxAfter = nationAfter["aux"]!!.jsonObject
                .mapValues { (_, v) -> v.jsonPrimitive.int }

            // --- draft: actor general + city + nation(국고/aux를 nationBefore 그대로 적재) ---
            val nation = Nation(
                id = c.before.general.int("nation"),
                level = 2,
                capitalCityId = P2GoldenSupport.capitalOf(c.before.general.int("nation")),
                name = P2GoldenSupport.nationNameOf(c.before.general.int("nation")),
                gold = nationBefore["gold"]!!.jsonPrimitive.int,
                rice = nationBefore["rice"]!!.jsonPrimitive.int,
                tech = P2GoldenSupport.techOf(c.before.general.int("nation")),
                meta = linkedMapOf("aux" to auxBefore),
            )
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

            // --- 로그 byte-match (actor action 1줄 + broadcast 0줄) ---
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // --- general after-delta(phpRound half-away; exp/ded += 120) ---
            val ag = c.after.general
            val g = draft.general
            assertEquals(ag.int("experience"), phpRound(g.experience), "[$command/${c.name}] general.experience")
            assertEquals(ag.int("dedication"), phpRound(g.dedication), "[$command/${c.name}] general.dedication")
            assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "[$command/${c.name}] meta.explevel")

            // --- nation after-delta(gold/rice 차감 + aux[can_화륜차사용]=1, 기존 aux 보존) ---
            val n = draft.nation!!
            assertEquals(nationAfter["gold"]!!.jsonPrimitive.int, n.gold, "[$command/${c.name}] nation.gold")
            assertEquals(nationAfter["rice"]!!.jsonPrimitive.int, n.rice, "[$command/${c.name}] nation.rice")
            @Suppress("UNCHECKED_CAST")
            val resultAux = n.meta["aux"] as Map<String, Any?>
            assertEquals(auxAfter.keys.toList(), resultAux.keys.toList(),
                "[$command/${c.name}] nation aux key set + insertion order")
            for ((k, v) in auxAfter) {
                assertEquals(v, (resultAux[k] as Number).toInt(), "[$command/${c.name}] nation aux[$k]")
            }
        }
    }
}
