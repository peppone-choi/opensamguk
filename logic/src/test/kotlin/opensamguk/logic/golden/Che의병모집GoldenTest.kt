package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
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
import opensamguk.logic.domain.metaInt
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA F4-C3-CHIEF — che_의병모집(CheUibyeongMojip) PHP-골든 draw-for-draw 바이트 게이트.
 *
 * 실제 Kotlin 리졸버를 [CommandRegistry]로 해석해 구동하고(REGISTRY RESOLUTION 필수),
 * golden/p2/che_의병모집-fixtures.json(hiddenSeed a2db167c.., acting gid 152, NPC 6명, draw_count=77)를
 * 바이트/draw 오라클로 삼는다.
 *
 * 검증 항목(게이트 레시피):
 *  - 6-토큰 시드 문자열이 골든 seedString과 byte-equal(rngFor GUARD).
 *  - RNG draw 스트림: 골든 draw_stream을 method/args(min·max·items)/result(또는 choiceIndex)/draw_count까지
 *    순서대로 byte-match. 의병모집은 NPC 생성 루프(pickGeneralFromPool 이름 3택 ×6 → setKillturn
 *    nextRangeInt(64,70) → fillRemainSpecAsRandom[affinity, choiceUsingWeight(무/지) 내부 nextFloat1+래퍼,
 *    statMain nextRangeInt(0,10), statOther nextRangeInt(0,5), '무'면 dex choice, ego choice] →
 *    getRandTurn nextRangeInt(0,7199)+nextRangeInt(0,999999))로 77 draw를 소비한다.
 *  - acting logLines(`의병모집 발동! <1>HH:MM</>`) + broadcastLines([]) byte-match.
 *  - general after-delta(experience/dedication += 15, phpRound half-away; explevel 불변), actor city 불변.
 *
 * createGenCnt(=6) + avgGen['dex_t'](=0) + env.turnterm(=120 → 60*term-1=7199)는 PHP DB 집계 쿼리 결과로,
 * 메모리 드래프트엔 없는 월드 입력 → 골든 draw_stream에서 직접 역산한 documented fixture input을 args로 주입.
 * (이름 6쌍×3 draw → createGenCnt=6; getRandTurn min/max 7199/999999 → turnterm=120; dex choice 인자
 *  [[0,0,0,0]×3] → avgDexTotal=0.) 결코 fabricate하지 않고 오직 커밋된 골든에서 가져온다.
 */
class Che의병모집GoldenTest {

    private val pipeline = GeneralActionPipeline()
    private val registry = CommandRegistry(pipeline)

    /**
     * golden draw_stream을 그대로 기록하는 RandUtil 데코레이터. 각 draw의 method/min/max/items/result/
     * choiceIndex를 캡처한다. choiceUsingWeight는 내부 nextFloat1과 래퍼 entry를 모두 기록(골든도 2 entry).
     */
    private class RecordingRandUtil(inner: LiteHashDrbg) : RandUtil(inner) {
        data class Draw(
            val method: String,
            val min: Int? = null,
            val max: Int? = null,
            val items: List<String>? = null,
            val choiceIndex: Int? = null,
            val result: String? = null,
        )

        val draws = mutableListOf<Draw>()

        /** 기록 억제 플래그 — choice 내부에서 인덱스 draw를 spurious entry 없이 재현하기 위함. */
        private var suppress = false

        override fun nextFloat1(): Double {
            val r = super.nextFloat1()
            if (!suppress) draws.add(Draw(method = "nextFloat1", result = r.toString()))
            return r
        }

        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            val r = super.nextRangeInt(minInclusive, maxInclusive)
            if (!suppress) {
                draws.add(Draw(method = "nextRangeInt", min = minInclusive, max = maxInclusive, result = r.toString()))
            }
            return r
        }

        override fun <T> choice(items: List<T>): T {
            // RandUtil.choice == items[rng.nextInt(size-1)] == items[nextRangeInt(0, size-1)].
            // suppress로 spurious nextRangeInt entry를 막고 실제 drawn index를 캡처(중복값 리스트 안전).
            require(items.isNotEmpty())
            val prev = suppress
            suppress = true
            val idx = nextRangeInt(0, items.size - 1)
            suppress = prev
            val r = items[idx]
            draws.add(
                Draw(
                    method = "choice",
                    items = items.map { tokenOf(it) },
                    choiceIndex = idx,
                    result = tokenOf(r),
                ),
            )
            return r
        }

        override fun choiceUsingWeight(items: Map<String, Double>): String {
            // 내부 nextFloat1은 별도 entry로 기록되고(억제 안 함), 래퍼는 추가 entry로 기록(골든도 2 entry).
            val r = super.choiceUsingWeight(items)
            draws.add(Draw(method = "choiceUsingWeight", items = items.keys.toList(), result = r))
            return r
        }

        /** choice 항목 토큰화 — 문자열은 그대로, 그 외(dex 배열 등)는 result 비교 제외(items만 검증). */
        private fun tokenOf(v: Any?): String = when (v) {
            is String -> v
            else -> ""
        }
    }

    @Test
    fun `che_의병모집 golden byte-matches the PHP action log, 77-draw RNG stream, and post-state`() {
        val command = "che_의병모집"
        val f = P2GoldenSupport.load(command)
        val def = registry.resolve(command)
        // REGISTRY RESOLUTION required — wired key == seed-token / golden command.
        assertEquals(command, def.key, "$command registry key")

        // 커밋된 골든의 case별 draws 블록(byte 오라클; 절대 fabricate 없음).
        val goldenDrawsByCase = parseGoldenDraws(command)

        for ((idx, c) in f.cases.withIndex()) {
            val general = P2GoldenSupport.generalFrom(c.generalId, c.before.general)
            val city = P2GoldenSupport.cityFrom(c.cityId, c.before.city)
            val nation = P2GoldenSupport.nationFrom(c, gold = 100000, rice = 100000,
                tech = P2GoldenSupport.techOf(c.before.general.int("nation")))
            val draft = GeneralActionDraft(general, city, nation)

            // GUARD: 6-토큰 시드 재구성 == 골든 seedString. 그 위에 기록 RNG를 잡는다.
            val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, def.rawClassName)
            assertEquals(c.seedString, seed, "[$command/${c.name}] seedString must byte-equal the golden PHP oracle")
            val rng = RecordingRandUtil(LiteHashDrbg(seed))

            // 월드 집계 입력(documented fixture input — 골든 draw_stream에서 역산).
            val golden = goldenDrawsByCase[idx]
            val args = c.arg.orEmpty() + mapOf(
                "createGenCnt" to deriveCreateGenCnt(golden.stream),
                "turnterm" to deriveTurnTerm(golden.stream),
                "avgDexTotal" to 0,  // dex choice 인자 [[0,0,0,0]×3] → dex_t 평균 0.
            )

            val ctx = GeneralActionResolveContext(
                draft = draft,
                rng = rng,
                env = P2GoldenSupport.envOf(c),
                month = c.env.month,
                date = P2GoldenSupport.dateOf(c),
                args = args,
                generalName = P2GoldenSupport.nameOf(c.generalId),
            )

            def.resolve(ctx)

            // --- draw-for-draw: draw_count + 각 draw method/args/result byte-match ---
            assertEquals(golden.count, rng.draws.size, "[$command/${c.name}] draw_count")
            assertEquals(golden.stream.size, rng.draws.size, "[$command/${c.name}] golden draw_stream size")
            for (i in golden.stream.indices) {
                val g = golden.stream[i]
                val a = rng.draws[i]
                val tag = "[$command/${c.name}] draw #$i"
                assertEquals(g.method, a.method, "$tag method")
                when (g.method) {
                    "nextRangeInt" -> {
                        assertEquals(g.min, a.min, "$tag min")
                        assertEquals(g.max, a.max, "$tag max")
                        assertEquals(g.result, a.result, "$tag result")
                    }
                    "nextFloat1" ->
                        // float 결과는 포맷 차이를 피해 파싱된 Double 동등성으로 비교(DRBG byte-exact).
                        assertEquals(g.result!!.toDouble(), a.result!!.toDouble(), 0.0, "$tag nextFloat1 result")
                    "choice" -> {
                        assertEquals(g.items, a.items, "$tag choice items")
                        assertEquals(g.choiceIndex, a.choiceIndex, "$tag choiceIndex")
                        // 문자열 결과(이름/인성)만 비교; dex-배열 choice는 items+index로 검증(result 토큰 "").
                        if (g.result != null && g.result.isNotEmpty()) {
                            assertEquals(g.result, a.result, "$tag choice result")
                        }
                    }
                    "choiceUsingWeight" -> {
                        assertEquals(g.items, a.items, "$tag weight items")
                        assertEquals(g.result, a.result, "$tag weight result")
                    }
                }
            }

            // --- 로그 byte-match ---
            assertEquals(c.logLines, ctx.logs(), "[$command/${c.name}] action-log byte-match")
            assertEquals(c.broadcastLines, ctx.globalActionLogs(), "[$command/${c.name}] broadcast byte-match")

            // --- general after-delta(exp/ded += 15, phpRound half-away; explevel 불변) ---
            val ag = c.after.general
            val gAfter = draft.general
            assertEquals(ag.int("experience"), phpRound(gAfter.experience), "[$command/${c.name}] general.experience")
            assertEquals(ag.int("dedication"), phpRound(gAfter.dedication), "[$command/${c.name}] general.dedication")
            assertEquals(ag.int("explevel"), metaInt(gAfter.meta, "explevel"), "[$command/${c.name}] meta.explevel")
            assertEquals(ag.int("gold"), gAfter.gold, "[$command/${c.name}] general.gold")

            // --- actor city 불변(의병모집은 도시 상태 미변경) ---
            assertAfterCity(command, c, draft.city)
        }
    }

    private fun assertAfterCity(command: String, c: P2GoldenSupport.Case, city: City) {
        val ac = c.after.city
        val tag = "[$command/${c.name}]"
        assertEquals(ac.int("comm"), city.commerce, "$tag city.comm")
        assertEquals(ac.int("agri"), city.agriculture, "$tag city.agri")
        if (ac.has("pop")) assertEquals(ac.int("pop"), city.population, "$tag city.pop")
        assertEquals(ac.double("trust"), city.trust, 1e-9, "$tag city.trust")
    }

    // ---- 골든 draws 블록 파서(커밋된 fixture JSON에서 직접; 절대 fabricate 없음) ----

    private data class GoldenDraw(
        val method: String,
        val min: Int? = null,
        val max: Int? = null,
        val items: List<String>? = null,
        val choiceIndex: Int? = null,
        val result: String? = null,
    )

    private data class GoldenCaseDraws(val count: Int, val stream: List<GoldenDraw>)

    private fun parseGoldenDraws(command: String): List<GoldenCaseDraws> {
        val text = Che의병모집GoldenTest::class.java.classLoader
            .getResourceAsStream("golden/p2/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        return Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray.map { el ->
            val draws = el.jsonObject["draws"]!!.jsonObject
            val count = draws["draw_count"]!!.jsonPrimitive.int
            val stream = draws["draw_stream"]!!.jsonArray.map { parseGoldenDraw(it.jsonObject) }
            GoldenCaseDraws(count, stream)
        }
    }

    private fun parseGoldenDraw(o: JsonObject): GoldenDraw {
        val method = o["method"]!!.jsonPrimitive.content
        val argsEl = o["args"]
        var min: Int? = null
        var max: Int? = null
        var items: List<String>? = null
        if (argsEl is JsonObject) {
            min = argsEl["min"]?.jsonPrimitive?.intOrNull
            max = argsEl["max"]?.jsonPrimitive?.intOrNull
            items = (argsEl["items"] as? JsonArray)?.map { itemToken(it) }
        }
        // result는 choice/weight는 문자열, nextRangeInt/nextFloat1은 숫자 — 토큰 비교를 위해 content로.
        val resultEl = o["result"]
        val result = when (resultEl) {
            null -> null
            is JsonArray -> ""          // dex-배열 result → 토큰 "" (items+index로 검증).
            else -> resultEl.jsonPrimitive.content
        }
        val choiceIndex = o["choiceIndex"]?.jsonPrimitive?.intOrNull
        return GoldenDraw(method, min, max, items, choiceIndex, result)
    }

    /** choice items 토큰 — 문자열은 그대로, 배열(dex 후보)은 "". */
    private fun itemToken(el: kotlinx.serialization.json.JsonElement): String = when (el) {
        is JsonArray -> ""
        else -> el.jsonPrimitive.content
    }

    /** 골든 draw_stream에서 NPC 수 역산: pickGeneralFromPool 이름 3택이 createGenCnt*3 선행 → setKillturn 카운트. */
    private fun deriveCreateGenCnt(stream: List<GoldenDraw>): Int =
        stream.count { it.method == "nextRangeInt" && it.min == 64 && it.max == 70 }

    /** 골든 draw_stream에서 turnterm 역산: getRandTurn의 nextRangeInt(0, 60*term-1) → max=7199 → term=120. */
    private fun deriveTurnTerm(stream: List<GoldenDraw>): Int {
        val turnSec = stream.firstOrNull { it.method == "nextRangeInt" && it.min == 0 && (it.max ?: 0) >= 1000 && it.max != 999999 }
        val max = turnSec?.max ?: 7199
        return (max + 1) / 60
    }
}
