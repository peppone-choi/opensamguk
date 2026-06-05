package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.logic.actions.vote.MORE_PROB
import opensamguk.logic.actions.vote.UniqueItemEntry
import opensamguk.logic.actions.vote.VoteLotteryInputs
import opensamguk.logic.actions.vote.deriveItemPool
import opensamguk.logic.actions.vote.deriveSurveyProb0
import opensamguk.logic.actions.vote.tryUniqueItemLottery
import opensamguk.logic.actions.vote.voteUniqueSeed
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * voteUnique 추첨 G-GATE — RNG 패러티 CORE 의 페이즈 게이트.
 *
 * 골든: `golden/vote/vote-unique-fixtures.json` (16 fixtures). PHP grand truth:
 *   - Vote.php:111-117 추첨 전용 RNG 시드 = simpleSerialize(hiddenSeed,'voteUnique',voteID,generalID)
 *   - func.php:1611-1703 tryUniqueItemLottery ('설문조사' 분기) + func.php:1487-1609 giveRandomUniqueItem.
 *
 * ## 무엇을 HARD 검증하는가
 * 각 fixture 마다:
 *   1. fixture 의 seedString 과 동일한 `voteUniqueSeed(hiddenSeed, voteId, generalId)` 로 [LiteHashDrbg] 를 만들고,
 *   2. [VoteLotteryDrawRecorder] 로 감싼 뒤,
 *   3. fixture 의 PINNED 입력(genCount/itemTypeCnt/maxCnt/prob0/moreProb/itemPool)을 [tryUniqueItemLottery] 에 먹여
 *      실제 포팅 로직을 **실행**한다 (골든 draw 를 재발행하는 게 아니라, 우리 로직이 만들어내는 스트림을 측정).
 *   4. 만들어진 draw 스트림이 골든의 draws 와 DRAW-FOR-DRAW(seq/method/args.prob/result) 일치하는지,
 *      cursor(stateIdxBefore/bufferIdxBefore)까지 일치하는지, draw COUNT 가 일치하는지,
 *      그리고 outcome(won + itemType/itemCode)이 일치하는지 assert 한다.
 *
 * 한 draw 라도 extra/missing/reorder 면 downstream 전체가 desync 되므로, 이 게이트가 RNG 패러티 CORE 다.
 * mismatch 시 골든은 grand truth — 절대 약화하지 않고 Kotlin 구현을 고친다.
 */
class VoteLotteryReplayGateTest {

    private companion object {
        const val EXPECTED_FIXTURE_COUNT = 16
        val HIDDEN_SEED_RE = Regex("^[0-9a-f]{32}$")
    }

    private val root: JsonObject = Json.parseToJsonElement(
        VoteLotteryReplayGateTest::class.java.classLoader
            .getResourceAsStream("golden/vote/vote-unique-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8),
    ).jsonObject

    private val hiddenSeed: String = root["hiddenSeed"]!!.jsonPrimitive.content
    private val fixtures: JsonArray = root["fixtures"]!!.jsonArray

    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.int

    // ── census / shape invariants ─────────────────────────────────────────────────────────────────────

    @Test
    fun `golden ships exactly 16 fixtures with a 32-char lowercase hex hiddenSeed`() {
        assertEquals(EXPECTED_FIXTURE_COUNT, fixtures.size, "fixture count must be 16")
        assertTrue(
            HIDDEN_SEED_RE.matches(hiddenSeed),
            "hiddenSeed must be ^[0-9a-f]{32}$ (a byte-0 desync desyncs every per-vote DRBG): '$hiddenSeed'",
        )
    }

    @Test
    fun `the ported seed builder reproduces every fixture seedString byte-for-byte`() {
        for (fx in fixtures.map { it.jsonObject }) {
            val voteId = fx.i("voteId")
            val generalId = fx.i("generalId")
            val expected = fx.s("seedString")
            val actual = voteUniqueSeed(hiddenSeed, voteId, generalId)
            assertEquals(
                expected, actual,
                "voteUniqueSeed(voteId=$voteId, generalId=$generalId) must equal the pinned simpleSerialize seedString",
            )
        }
    }

    // ── prob0 / moreProb formula parity (Task 3) ──────────────────────────────────────────────────────

    @Test
    fun `ported prob0 formula reproduces the pinned prob0 and moreProb`() {
        // 설문조사 분기: genCount=8, itemTypeCnt=4 → prob0 = (1/(8*4*0.7/3) * 1, clamp 0.25, /sqrt(7)).
        val derived = deriveSurveyProb0(genCount = 8, itemTypeCnt = 4)
        // 모든 fixture 의 PINNED prob0 가 동일하므로 첫 fixture 의 값과도 대조.
        val pinnedProb0 = fixtures[0].jsonObject["inputs"]!!.jsonObject["prob0"]!!.jsonPrimitive.double
        assertEquals(pinnedProb0, derived, "deriveSurveyProb0(8,4) must reproduce the golden's pinned prob0")
        assertEquals(0.050620241920878654, derived, "prob0 byte-value")

        val pinnedMore = fixtures[0].jsonObject["inputs"]!!.jsonObject["moreProb"]!!.jsonPrimitive.double
        assertEquals(pinnedMore, MORE_PROB, "moreProb = pow(10, 1/4) must reproduce the golden's pinned moreProb")
        assertEquals(1.7782794100389228, MORE_PROB, "moreProb byte-value")
    }

    @Test
    fun `ported itemPool derivation matches the golden pinned pool order and weights`() {
        // GameConst allItems mirror — 삽입-순서 보존 맵. weight==0 항목(che_*_01..06)은 풀에서 제외돼야 한다.
        // 골든의 PINNED itemPool 자체를 allItems 형태로 역구성해 derive 가 동일 순서+가중을 재현하는지 본다.
        val pinnedPool = fixtures[0].jsonObject["inputs"]!!.jsonObject["itemPool"]!!.jsonArray
            .map { it.jsonObject }
            .map { UniqueItemEntry(it.s("itemType"), it.s("itemCode"), it.i("weight")) }

        // allItems 재구성: weight 0 더미를 각 타입 앞에 끼워 넣어, derive 가 그것들을 정확히 건너뛰는지 검증.
        val allItems = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        for (e in pinnedPool) {
            val byType = allItems.getOrPut(e.itemType) { LinkedHashMap() }
            byType[e.itemCode] = e.weight
        }
        // 각 타입에 weight 0 더미 1개를 맨 앞에 추가 (실제 GameConst 의 che_*_01..06 과 동일 의미).
        val withZeros = LinkedHashMap<String, Map<String, Int>>()
        for ((type, codes) in allItems) {
            val merged = LinkedHashMap<String, Int>()
            merged["__zero_${type}__"] = 0
            merged.putAll(codes)
            withZeros[type] = merged
        }

        val derived = deriveItemPool(withZeros)
        assertEquals(pinnedPool.size, derived.size, "derived pool size must equal golden pinned pool (zeros skipped)")
        for ((idx, expected) in pinnedPool.withIndex()) {
            val actual = derived[idx]
            assertEquals(
                expected, actual,
                "pool[$idx] order+weight mismatch — golden=$expected derived=$actual",
            )
        }
    }

    // ── THE GATE: draw-for-draw replay over all 16 fixtures ───────────────────────────────────────────

    @Test
    fun `every fixture replays the committed golden draw-for-draw and outcome`() {
        var replayed = 0
        var matched = 0
        val firstFailures = ArrayList<String>()

        for (fxEl in fixtures) {
            val fx = fxEl.jsonObject
            replayed += 1
            val failure = replayFixture(fx)
            if (failure == null) {
                matched += 1
            } else if (firstFailures.size < 16) {
                firstFailures.add(failure)
            }
        }

        if (matched != replayed) {
            throw AssertionError(
                "voteUnique draw-for-draw gate: $matched / $replayed fixtures matched.\n" +
                    "First failures (≤16):\n" + firstFailures.joinToString("\n"),
            )
        }
        assertEquals(replayed, matched)
        assertEquals(EXPECTED_FIXTURE_COUNT, matched, "all 16 fixtures must replay green")
    }

    /**
     * 한 fixture 를 실제 포팅 로직으로 실행하고 produced 스트림 + outcome 을 골든과 대조.
     * @return null on full match, else a human-readable first-divergence message.
     */
    private fun replayFixture(fx: JsonObject): String? {
        val voteId = fx.i("voteId")
        val generalId = fx.i("generalId")
        val tag = "vote($voteId,$generalId)"

        // 1) fixture 의 seedString 과 동일한 시드로 recorder 를 만든다.
        val seedString = fx.s("seedString")
        // 우리 시드 빌더가 만든 시드가 골든 seedString 과 같음은 위 테스트가 이미 보장 — 여기선 seedString 그대로 사용.
        val recorder = VoteLotteryDrawRecorder(LiteHashDrbg(seedString))

        // 2) PINNED 입력을 구성.
        val inputsJson = fx["inputs"]!!.jsonObject
        val itemPool = inputsJson["itemPool"]!!.jsonArray.map { it.jsonObject }.map {
            UniqueItemEntry(it.s("itemType"), it.s("itemCode"), it.i("weight"))
        }
        val inputs = VoteLotteryInputs(
            genCount = inputsJson.i("genCount"),
            itemTypeCnt = inputsJson.i("itemTypeCnt"),
            maxCnt = inputsJson.i("maxCnt"),
            prob0 = inputsJson["prob0"]!!.jsonPrimitive.double,
            moreProb = inputsJson["moreProb"]!!.jsonPrimitive.double,
            itemPool = itemPool,
        )

        // 3) 실제 포팅 로직 실행 (golden draw 를 재발행하는 게 아님). npc=0 → inertNpc=false.
        val result = tryUniqueItemLottery(recorder, inputs, inertNpc = false)

        // 4a) draw-for-draw 대조.
        val goldenDraws = fx["draws"]!!.jsonArray.map { it.jsonObject }
        val produced = recorder.drawStream()
        if (produced.size != goldenDraws.size) {
            return "$tag draw COUNT mismatch — golden ${goldenDraws.size} vs kotlin ${produced.size}"
        }
        for ((seq, gd) in goldenDraws.withIndex()) {
            val pd = produced[seq]
            val gMethod = gd.s("method")
            if (gMethod != pd.method) {
                return "$tag seq=$seq method mismatch — golden=$gMethod kotlin=${pd.method}"
            }
            val gState = gd["stateIdxBefore"]!!.jsonPrimitive.int.toLong()
            val gBuffer = gd["bufferIdxBefore"]!!.jsonPrimitive.int
            if (gState != pd.stateIdxBefore || gBuffer != pd.bufferIdxBefore) {
                return "$tag seq=$seq cursor mismatch — golden=($gState,$gBuffer) kotlin=(${pd.stateIdxBefore},${pd.bufferIdxBefore})"
            }
            val gConsumed = gd["consumed"]!!.jsonPrimitive.booleanOrNull ?: true
            if (gConsumed != pd.consumed) {
                return "$tag seq=$seq consumed mismatch — golden=$gConsumed kotlin=${pd.consumed}"
            }
            val argMismatch = argMismatch(gMethod, gd["args"]!!.jsonObject, pd)
            if (argMismatch != null) return "$tag seq=$seq $argMismatch"
            val resultMismatch = resultMismatch(gMethod, gd["result"], pd.result)
            if (resultMismatch != null) return "$tag seq=$seq $resultMismatch"
        }

        // 4b) outcome 대조.
        val outcome = fx["outcome"]!!.jsonObject
        val gWon = outcome["won"]!!.jsonPrimitive.booleanOrNull ?: false
        if (gWon != result.won) {
            return "$tag outcome.won mismatch — golden=$gWon kotlin=${result.won}"
        }
        if (gWon) {
            val gType = outcome["itemType"]!!.jsonPrimitive.content
            val gCode = outcome["itemCode"]!!.jsonPrimitive.content
            if (gType != result.itemType || gCode != result.itemCode) {
                return "$tag outcome item mismatch — golden=($gType,$gCode) kotlin=(${result.itemType},${result.itemCode})"
            }
        }
        return null
    }

    /** nextBool 의 prob 인자를 골든과 대조 (choiceUsingWeightPair 는 풀 전체가 PINNED 입력이므로 size 만 본다). */
    private fun argMismatch(method: String, gArgs: JsonObject, pd: VoteLotteryDrawRecorder.Draw): String? {
        if (method == "nextBool") {
            val gProb = gArgs["prob"]!!.jsonPrimitive.double
            val pProb = pd.args["prob"] as Double
            if (gProb != pProb) return "args.prob mismatch — golden=$gProb kotlin=$pProb"
        }
        if (method == "choiceUsingWeightPair") {
            // 골든의 pool 길이와 우리가 먹인 풀 길이가 일치해야 한다.
            val gPoolSize = gArgs["pool"]!!.jsonArray.size
            val pSize = pd.args["size"] as Int
            if (gPoolSize != pSize) return "choiceUsingWeightPair pool size mismatch — golden=$gPoolSize kotlin=$pSize"
        }
        return null
    }

    /** 결과 대조: nextBool=Boolean, choiceUsingWeightPair=["type","code"]. */
    private fun resultMismatch(method: String, gResult: kotlinx.serialization.json.JsonElement?, pResult: Any?): String? {
        if (method == "nextBool") {
            val gBool = gResult!!.jsonPrimitive.booleanOrNull
            if (gBool != (pResult as? Boolean)) return "result mismatch — golden=$gBool kotlin=$pResult"
            return null
        }
        if (method == "choiceUsingWeightPair") {
            val gArr = gResult!!.jsonArray
            val gType = gArr[0].jsonPrimitive.content
            val gCode = gArr[1].jsonPrimitive.content
            @Suppress("UNCHECKED_CAST")
            val pPair = pResult as Pair<String, String>
            if (gType != pPair.first || gCode != pPair.second) {
                return "result mismatch — golden=[$gType,$gCode] kotlin=[${pPair.first},${pPair.second}]"
            }
        }
        return null
    }
}
