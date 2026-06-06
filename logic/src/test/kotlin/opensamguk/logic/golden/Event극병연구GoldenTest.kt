package opensamguk.logic.golden

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
 * AREA F4-C3-CHIEF — event_극병연구(EventGeukbyeongYeongu) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 실제 Kotlin 리졸버를 [CommandRegistry]로 해석해 구동하고(REGISTRY RESOLUTION 필수),
 * golden/p2/event_극병연구-fixtures.json(hiddenSeed a2db167c.., acting gid 152 ⓝ하진, nation 1 후한 사령)을
 * 바이트/draw 오라클로 삼는다.
 *
 * event_극병연구 run()(event_극병연구.php:68-107)은 RNG 미사용(deterministic, draw_count=0). 효과 순서:
 *   1) addExperience/addDedication += 5*(preReqTurn+1) = 5*24 = 120 (exp 4582→4702, ded 4120→4240)
 *   2) nation gold -= 100000(1000000→900000), rice -= 100000(1000000→900000), aux[can_극병사용]=1
 *      (기존 aux 키 can_국기변경/can_국호변경 뒤에 삽입 — LinkedHashMap 순서 보존)
 *   3) pushGeneralActionLog("<M>극병 연구</> 완료") — actor logs()에 나타나는 유일한 라인
 *      (pushGeneralHistoryLog/pushNationalHistoryLog는 별도 history 스코프 → actor logs() 미포함)
 *   4) increaseInheritancePoint(active_action, 1) — P6 seam(fixture inheritanceAfter.active_action=null)
 *
 * 검증 항목(게이트 레시피):
 *  - 6-토큰 시드 문자열이 골든 seedString과 byte-equal(rngFor GUARD).
 *  - RNG draw 스트림: deterministic(draw_count=0) → LiteHashDRBG 커서(stateIdx/bufferIdx)가 resolve 전/후 불변.
 *  - acting logLines + broadcastLines byte-match.
 *  - general after-delta(experience/dedication, phpRound half-away).
 *  - nation after-delta(gold/rice/aux[can_극병사용]).
 */
class Event극병연구GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `event_극병연구 golden byte-matches the PHP action log, zero-draw RNG, and post-state`() {
        val command = "event_극병연구"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        for (c in f.cases) {
            // --- draft: actor general + city + nation(국고/aux는 fixture nationBefore 그대로 시드) ---
            // P2GoldenSupport.nationFrom은 nationBefore의 gold/rice/aux를 읽지 않으므로(공유 헬퍼 미수정),
            // fixture 문서값으로 직접 구성: gold/rice 1000000, aux={can_국기변경:1, can_국호변경:1}(삽입 순서 보존).
            val nation = Nation(
                id = 1,
                level = 2,
                capitalCityId = P2GoldenSupport.capitalOf(1),
                name = P2GoldenSupport.nationNameOf(1),
                gold = 1_000_000,
                rice = 1_000_000,
                tech = P2GoldenSupport.techOf(1),
                meta = linkedMapOf(
                    "aux" to linkedMapOf<String, Any?>(
                        "can_국기변경" to 1,
                        "can_국호변경" to 1,
                    ),
                ),
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

            // --- 로그 byte-match ---
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // --- general after-delta(phpRound half-away): exp +120, ded +120 ---
            val ag = c.after.general
            val g = draft.general
            assertEquals(ag.int("experience"), phpRound(g.experience), "[$command/${c.name}] general.experience")
            assertEquals(ag.int("dedication"), phpRound(g.dedication), "[$command/${c.name}] general.dedication")
            assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "[$command/${c.name}] meta.explevel")
            assertEquals(ag.int("gold"), g.gold, "[$command/${c.name}] general.gold unchanged")
            assertEquals(ag.int("rice"), g.rice, "[$command/${c.name}] general.rice unchanged")

            // --- nation after-delta: gold/rice -= 100000, aux[can_극병사용]=1(기존 키 순서 보존) ---
            val n = draft.nation!!
            assertEquals(900_000, n.gold, "[$command/${c.name}] nation.gold -= reqGold")
            assertEquals(900_000, n.rice, "[$command/${c.name}] nation.rice -= reqRice")
            @Suppress("UNCHECKED_CAST")
            val aux = n.meta["aux"] as Map<String, Any?>
            assertEquals(1, (aux["can_극병사용"] as Number).toInt(), "[$command/${c.name}] aux[can_극병사용]=1")
            assertEquals(
                listOf("can_국기변경", "can_국호변경", "can_극병사용"),
                aux.keys.toList(),
                "[$command/${c.name}] aux insertion order preserved (can_극병사용 appended last)",
            )
        }
    }
}
