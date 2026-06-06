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
 * AREA F4-C3-CHIEF — event_무희연구(EventMuhuiYeongu) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 실제 Kotlin 리졸버를 [CommandRegistry]로 해석해 구동하고(REGISTRY RESOLUTION 필수),
 * golden/p2/event_무희연구-fixtures.json(hiddenSeed cbc88849.., 사령 gid 152 ⓝ하진, nation 1 후한)을
 * 바이트/draw 오라클로 삼는다.
 *
 * event_무희연구.php:run()(deterministic, draw_count=0) 실행 순서:
 *   1) addExperience/addDedication += 5*(getPreReqTurn()+1) = 120  (exp/ded가 gold/rice·로그보다 먼저)
 *   2) nation gold -= 100000, rice -= 100000, aux[can_무희사용]=1
 *   3) pushGeneralActionLog "<M>무희 연구</> 완료"   (actor logLines)
 *   4) pushGeneralHistoryLog "<M>무희 연구</> 완료"   (general-history 스코프 — actor logLines와 분리)
 *   5) pushNationalHistoryLog "<Y>ⓝ하진</>이 <M>무희 연구</> 완료" (national-history 스코프 — 분리)
 *   6) increaseInheritancePoint(active_action, 1)      (P6 유산 seam — 골든 inheritance before==after)
 *
 * 검증 항목(게이트 레시피):
 *  - 6-토큰 시드 문자열이 골든 seedString과 byte-equal.
 *  - RNG draw 스트림: deterministic(draw_count=0) → LiteHashDRBG 커서(stateIdx/bufferIdx)가 resolve 전/후 불변.
 *  - acting logLines + broadcastLines byte-match.
 *  - general after-delta(experience/dedication, phpRound half-away) — +120 각각.
 *  - nation after-delta: gold/rice -= 100000, aux에 can_무희사용=1 추가(기존 aux 보존 + 삽입 순서).
 */
class Event무희연구GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    @Test
    fun `무희연구 golden byte-matches the PHP action log, zero-draw RNG, and post-state delta`() {
        val command = "event_무희연구"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        assertEquals(command, def.key, "$command registry key")

        // 골든 fixture의 nationBefore/nationAfter(aux 포함)는 P2GoldenSupport 파서에 없는 필드 →
        // 리소스 raw JSON에서 직접 케이스를 읽어 nation 초기 상태/검증값을 구성한다.
        val rootText = P2GoldenSupport::class.java.classLoader
            .getResourceAsStream("golden/p2/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val rootCase = Json.parseToJsonElement(rootText).jsonObject["cases"]!!.jsonArray.first().jsonObject
        val nationBefore = rootCase["nationBefore"]!!.jsonObject
        val nationAfter = rootCase["nationAfter"]!!.jsonObject
        val auxBefore = nationBefore["aux"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
        val auxAfter = nationAfter["aux"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }

        val c = f.cases.first()

        // --- draft: actor general + city + nation(사령) ---
        // nation은 골든 nationBefore 그대로: gold/rice 1,000,000 + aux{can_국기변경,can_국호변경}.
        val nation = Nation(
            id = nationBefore["nation"]!!.jsonPrimitive.int,
            level = 2,
            capitalCityId = P2GoldenSupport.capitalOf(nationBefore["nation"]!!.jsonPrimitive.int),
            name = P2GoldenSupport.nationNameOf(nationBefore["nation"]!!.jsonPrimitive.int),
            gold = nationBefore["gold"]!!.jsonPrimitive.int,
            rice = nationBefore["rice"]!!.jsonPrimitive.int,
            meta = linkedMapOf("aux" to LinkedHashMap<String, Any?>().apply { putAll(auxBefore) }),
        )
        val draft = GeneralActionDraft(
            P2GoldenSupport.generalFrom(c.generalId, c.before.general),
            P2GoldenSupport.cityFrom(c.cityId, c.before.city),
            nation,
        )

        // --- RNG: 6-토큰 시드를 직접 재구성해 DRBG 핸들을 잡는다(0-draw 커서 비교용). ---
        val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
        assertEquals(c.seedString, seed, "[$command] seedString must byte-equal the golden PHP oracle")
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
            "[$command] DRBG stateIdx must be unchanged (golden draw_count=0)")
        assertEquals(bufferBefore, drbg.peekBufferIdx(),
            "[$command] DRBG bufferIdx must be unchanged (golden draw_count=0)")

        // --- 로그 byte-match (actor logLines + broadcast) ---
        assertEquals(c.logLines, ctx.logs(), "[$command] action-log byte-match")
        assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command] broadcast byte-match")

        // --- general after-delta(phpRound half-away): exp/ded += 120 ---
        val ag = c.after.general
        val g = draft.general
        assertEquals(ag.int("experience"), phpRound(g.experience), "[$command] general.experience (+120)")
        assertEquals(ag.int("dedication"), phpRound(g.dedication), "[$command] general.dedication (+120)")
        assertEquals(ag.int("explevel"), metaInt(g.meta, "explevel"), "[$command] meta.explevel unchanged")
        assertEquals(ag.int("gold"), g.gold, "[$command] actor gold unchanged")

        // --- nation after-delta: gold/rice -= 100000, aux에 can_무희사용=1 추가(기존 보존 + 삽입 순서) ---
        val n = draft.nation!!
        assertEquals(nationAfter["gold"]!!.jsonPrimitive.int, n.gold, "[$command] nation gold -= reqGold")
        assertEquals(nationAfter["rice"]!!.jsonPrimitive.int, n.rice, "[$command] nation rice -= reqRice")

        @Suppress("UNCHECKED_CAST")
        val resultAux = (n.meta["aux"] as Map<String, Any?>).mapValues { (it.value as Number).toInt() }
        assertEquals(auxAfter, resultAux, "[$command] nation aux gains can_무희사용=1 (existing aux preserved)")
        // 삽입 순서 — 신규 키는 끝에 append(PHP nation['aux'][key]=value + Json::encode 순서 보존).
        assertEquals(auxAfter.keys.toList(), resultAux.keys.toList(), "[$command] nation aux insertion order")
    }
}
