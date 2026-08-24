package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.logic.ai.AiSeed
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P5 G-GATE (Task GT3) — the **first real draw-for-draw pass** over the scenario-1010 PHP GeneralAI golden
 * banked at `golden/p5/ai-turn-000.json .. ai-turn-173.json` + `npc-census-1010.json` (GT1).
 *
 * ## What the banked golden supports (and what it does NOT)
 * The GT1 capture (`tools/php-golden/capture_ai.php`) ran the REAL PHP `GeneralAI::chooseNationTurn` /
 * `chooseGeneralTurn` over the LIVE installed scenario_1010 DB, with ONLY the per-general `"GeneralAI"` rng
 * swapped to a `RandUtilDrawRecorder`. Each fixture therefore banks, per due general per turn:
 *  - the `seedString` = `Util::simpleSerialize(hiddenSeed,'GeneralAI',year,month,generalId)` (the load-bearing
 *    DRBG key),
 *  - the ordered `drawStream[]` `{seq, method, args, result, consumed, stateIdxBefore, bufferIdxBefore}` the
 *    live AI consumed,
 *  - the chosen `(chosenActionCode RAW, chosenRawArgs, reason)` (+ the nation prefix in `nationTurn`).
 *
 * It does **NOT** bank the scenario-1010 GAME-ENTITY ROWS (the 174 general rows, 94 cities, 2 nations,
 * diplomacy, nation_env) the live AI branched on — the capture read them straight from the live DB and never
 * serialized them. (See `capture_ai.php`: only the AI selection is driven, the world is the live install.)
 *
 * ### Consequence (the structural gate split — cataloged, NOT faked)
 *  - **Dimension (a-RNG) — the DRBG byte-stream parity** IS fully replayable from the banked golden and is
 *    asserted HARD here: rebuild the per-general `LiteHashDrbg(seedString)`, RE-ISSUE the golden's recorded
 *    `method+args` sequence through the symmetric [BattleDrawRecorder], and assert the produced stream equals
 *    the golden VALUE-for-value AND CURSOR-for-cursor (stateIdx/bufferIdx) + the `consumed` short-circuit flag,
 *    at the FIRST divergent draw, plus the draw COUNT. This proves the Kotlin RNG kernel reproduces the EXACT
 *    byte stream the live PHP AI pulled — for every method the live month-1 1010 window exercised
 *    (nextBool short-circuit / nextFloat1 / the 거병 `nextBool(0.0075*more)` gate / …).
 *  - **Dimension (a-SELECTION) — running the LIVE `chooseGeneralTurn` and asserting its chosen
 *    `(actionCode, RAW args, reason)`** requires materializing the scenario-1010 world into an
 *    `InMemoryTurnWorld` and driving `AiTurnAdapter` (`:app:game-engine`). The world rows were NEVER banked,
 *    and `:logic` has no access to the engine adapter. So the live-selection replay is **BLOCKED on a missing
 *    world fixture** — it is NOT faked, NOT stubbed with an invented world, and is cataloged precisely in
 *    as the GAP-WORLD cluster that GT3 surfaces.
 *  - **Dimension (b) downstream log/delta** likewise needs the resolved command over the live world (the
 *    EXISTING P2-P4 resolve gates) — same GAP-WORLD blocker; diplomacy families are EXCLUDED anyway (m10).
 *  - **Dimension (c) long-sim timeline** needs the multi-turn world advance — same GAP-WORLD blocker.
 *
 * ## The seed lineage + census invariants (asserted HARD — M7 quarantine validity)
 *  - the `hiddenSeed` is the manifest-pinned `^[0-9a-f]{32}$` value (R-GATE §2);
 *  - the npc census is 0/0 (npc5==0 && npc9off12==0) so the do선양 / 오랑캐임관 `ORDER BY RAND` quarantine
 *    (Q1) holds;
 *  - `AiSeed.seed(hidden,year,month,generalId)` reproduces every fixture's `seedString` byte-for-byte (the
 *    F-SEED lineage — a byte-0 desync here would desync EVERY per-general DRBG).
 *
 * On a mismatch the test fails with the FIRST divergent draw / the offending general-turn — the golden is
 * grand truth, NEVER weakened. Every divergence is also tallied into the catalog summary printed on failure.
 */
class AiReplayGateTest {

    private companion object {
        const val FIXTURE_COUNT = 174
        val HIDDEN_SEED_RE = Regex("^[0-9a-f]{32}$")
    }

    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(
            AiReplayGateTest::class.java.classLoader
                .getResourceAsStream("golden/p5/$name")!!
                .readBytes().toString(Charsets.UTF_8),
        ).jsonObject

    private fun fixtureNames(): List<String> = (0 until FIXTURE_COUNT).map { "ai-turn-%03d.json".format(it) }

    private fun JsonObject.s(key: String): String = this[key]!!.jsonPrimitive.content
    private fun JsonObject.i(key: String): Int = this[key]!!.jsonPrimitive.int

    // ── (A2) census + hiddenSeed format — the quarantine-validity invariants (M7, R-GATE §1/§2) ──────────

    @Test
    fun `the manifest hiddenSeed is a 32-char lowercase hex and matches every fixture`() {
        val census = load("npc-census-1010.json")
        val hidden = census.s("hiddenSeed")
        assertTrue(
            HIDDEN_SEED_RE.matches(hidden),
            "hiddenSeed must be ^[0-9a-f]{32}$ (R-GATE §2; a byte-0 desync desyncs every per-general DRBG): '$hidden'",
        )
        for (name in fixtureNames()) {
            val f = load(name)
            assertEquals(hidden, f.s("hiddenSeed"), "$name hiddenSeed must equal the pinned census hiddenSeed")
        }
    }

    @Test
    fun `the npc census is 0-0 so the do선양 오랑캐임관 ORDER BY RAND quarantine holds`() {
        val census = load("npc-census-1010.json")
        // The G4/Q1 quarantine (do선양 needs npc==5, 오랑캐임관 needs npc==9 lord) is valid ONLY while the
        // census stays 0/0 — exactly the Kotlin-world invariant the engine gate also asserts (R-GATE §1).
        assertEquals(0, census.i("npc5"), "scenario-1010 must seed ZERO npc==5 generals (do선양 unreachable)")
        assertEquals(0, census.i("npc9off12"), "scenario-1010 must seed ZERO npc==9 officer_level==12 lords (오랑캐임관 unreachable)")
        assertTrue(census["invariantHeld"]!!.jsonPrimitive.booleanOrNull == true, "census invariantHeld must be true")
        // both officer_level==12 rulers are npc==2 (so can선양 — gated on npc==5 — is never true).
        val lords = census["lords"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, lords.size, "scenario-1010 has exactly 2 officer_level==12 rulers (하진, 장각)")
        for (lord in lords) {
            assertEquals(2, lord.i("npc"), "every officer_level==12 ruler is npc==2 (lord ${lord["name"]})")
        }
    }

    // ── (A0) the F-SEED lineage — AiSeed reproduces every fixture's seedString byte-for-byte ─────────────

    @Test
    fun `AiSeed reproduces every fixture seedString byte-for-byte`() {
        val mismatches = ArrayList<String>()
        for (name in fixtureNames()) {
            val f = load(name)
            val expected = f.s("seedString")
            val actual = AiSeed.seed(f.s("hiddenSeed"), f.i("year"), f.i("month"), f.i("generalId"))
            if (expected != actual) {
                mismatches.add("$name (gid=${f.i("generalId")}): expected='$expected' actual='$actual'")
            }
        }
        assertTrue(
            mismatches.isEmpty(),
            "AiSeed.seed must byte-match the golden seedString (F-SEED lineage). Divergences:\n" +
                mismatches.joinToString("\n"),
        )
    }

    // ── (A1) the FIRST real draw-for-draw pass — the DRBG byte-stream parity over all 174 due-general turns ──

    /**
     * Replay the golden's recorded draw stream on a fresh per-general [LiteHashDrbg] seeded with the EXACT
     * `seedString`, RE-ISSUING each golden draw's `method+args` through the symmetric [BattleDrawRecorder].
     * Asserts the produced stream equals the golden VALUE-for-value AND CURSOR-for-cursor at the FIRST
     * divergent draw, plus the draw COUNT. This is the load-bearing RNG-kernel parity surface the live AI
     * consumed — the part the banked golden FULLY supports without the (un-banked) scenario-1010 world.
     */
    @Test
    fun `every due-general draw stream byte-matches value-for-value and cursor-for-cursor`() {
        var replayed = 0
        var matched = 0
        val clusters = LinkedHashMap<String, Int>()
        val firstFailures = ArrayList<String>()

        for (name in fixtureNames()) {
            val f = load(name)
            replayed += 1
            // Seed the recorder with the SAME simpleSerialize string the PHP recorder used (asserted ==AiSeed).
            val seedString = f.s("seedString")
            val recorder = BattleDrawRecorder(LiteHashDrbg(seedString))
            val golden = f["drawStream"]!!.jsonArray.map { it.jsonObject }

            // Re-issue the golden's recorded method+args in order on the recorder, capturing the FIRST
            // divergence (value | cursor | consumed | count). The recorder reproduces the live AI's stream
            // IFF the Kotlin DRBG kernel is byte-parity for the exact draws the AI made.
            val result = reissueAndDiff(name, f, seedString, recorder, golden)
            if (result == null) {
                matched += 1
            } else {
                val cluster = result.cluster
                clusters[cluster] = (clusters[cluster] ?: 0) + 1
                if (firstFailures.size < 25) firstFailures.add(result.message)
            }
        }

        if (matched != replayed) {
            val summary = buildString {
                append("AI draw-for-draw gate: $matched / $replayed due-general turns matched value+cursor.\n")
                append("Divergence clusters (by method/cause): ${clusters.entries.joinToString { "${it.key}=${it.value}" }}\n")
                append("First failures (≤25):\n")
                append(firstFailures.joinToString("\n"))
            }
            throw AssertionError(summary)
        }
        // green: all 174 due-general draw streams reproduce the live PHP stream byte-for-byte + cursor-for-cursor.
        assertEquals(replayed, matched)
    }

    /** A localized divergence for the catalog: the [cluster] key + a human [message] at the first bad draw. */
    private data class Divergence(val cluster: String, val message: String)

    /**
     * Re-issue the golden's recorded draws against [recorder]; return null on full match (value+cursor+
     * consumed+count) or the FIRST [Divergence]. The args drive the method exactly as the live AI called it.
     */
    private fun reissueAndDiff(
        name: String,
        f: JsonObject,
        seedString: String,
        recorder: BattleDrawRecorder,
        golden: List<JsonObject>,
    ): Divergence? {
        for ((seq, ge) in golden.withIndex()) {
            val method = ge.s("method")
            val args = ge["args"]
            // Drive the SAME method/args the live AI pulled off the stream.
            runCatching { issue(recorder, method, args) }.onFailure {
                return Divergence(
                    "$method:issue-error",
                    "$name seq=$seq FAILED to re-issue $method args=$args: ${it.message}",
                )
            }
            val produced = recorder.drawStream().getOrNull(seq)
                ?: return Divergence(
                    "$method:missing",
                    "$name seq=$seq: recorder produced no draw for golden $method args=$args",
                )

            val gState = ge["stateIdxBefore"]!!.jsonPrimitive.int.toLong()
            val gBuffer = ge["bufferIdxBefore"]!!.jsonPrimitive.int
            val gConsumed = ge["consumed"]!!.jsonPrimitive.booleanOrNull ?: true

            val cursorBad = gState != produced.stateIdxBefore || gBuffer != produced.bufferIdxBefore
            val consumedBad = gConsumed != produced.consumed
            val valueBad = !resultMatches(ge, produced)

            if (cursorBad || consumedBad || valueBad) {
                val reason = f["reason"]?.jsonPrimitive?.content ?: "?"
                val cause = when {
                    cursorBad -> "$method:cursor"
                    consumedBad -> "$method:consumed-shortcircuit"
                    else -> "$method:value"
                }
                return Divergence(
                    cause,
                    "$name (gid=${f.i("generalId")} reason=$reason) FIRST DIVERGENCE seq=$seq:\n" +
                        "  golden: method=$method args=$args result=${ge["result"]} consumed=$gConsumed " +
                        "cursor=($gState,$gBuffer)\n" +
                        "  kotlin: method=${produced.method} args=${produced.args} result=${produced.result} " +
                        "consumed=${produced.consumed} cursor=(${produced.stateIdxBefore},${produced.bufferIdxBefore})",
                )
            }
        }
        // draw COUNT — the recorder must have produced EXACTLY the golden's count (no extra/missing trailing draw).
        val producedCount = recorder.drawStream().size
        if (producedCount != golden.size) {
            return Divergence(
                "count",
                "$name (gid=${f.i("generalId")}) draw COUNT mismatch — golden ${golden.size} vs kotlin $producedCount",
            )
        }
        return null
    }

    /** Drive [recorder] with the golden's recorded method + verbatim args (the live AI's exact call). */
    private fun issue(recorder: BattleDrawRecorder, method: String, args: Any?) {
        when (method) {
            "nextFloat1" -> recorder.nextFloat1()
            "nextBit" -> recorder.nextBit()
            "nextBool" -> {
                val prob = (args as JsonObject)["prob"]!!.jsonPrimitive.double()
                recorder.nextBool(prob)
            }
            "nextRange" -> {
                val o = args as JsonObject
                recorder.nextRange(o["min"]!!.jsonPrimitive.double(), o["max"]!!.jsonPrimitive.double())
            }
            "nextRangeInt" -> {
                val o = args as JsonObject
                recorder.nextRangeInt(o["min"]!!.jsonPrimitive.int, o["max"]!!.jsonPrimitive.int)
            }
            "nextInt" -> {
                val o = args as JsonObject
                recorder.nextInt(o["min"]!!.jsonPrimitive.int, o["max"]!!.jsonPrimitive.int)
            }
            "choice" -> {
                // choice(items) — the cursor draw is nextInt(size-1); the recorded items reproduce the index.
                val items = ((args as JsonObject)["items"] as JsonArray).map { it.jsonPrimitive.content }
                recorder.choice(items)
            }
            else -> throw IllegalArgumentException("unknown draw method '$method'")
        }
    }

    /** Result equality tolerant of int/float/bool JSON shapes; choice asserts the index + value. */
    private fun resultMatches(ge: JsonObject, ae: BattleDrawRecorder.Draw): Boolean {
        if (ge.s("method") == "choice") {
            // the golden records result=items[idx]; the recorder records the same value + the choiceIndex.
            val gVal = ge["result"]?.jsonPrimitive?.content
            return gVal == (ae.result as? String)
        }
        val gp = ge["result"]!!.jsonPrimitive
        gp.booleanOrNull?.let { return it == (ae.result as? Boolean) }
        return when (val av = ae.result) {
            is Double -> gp.doubleOrNull == av
            is Int -> gp.intOrNull == av
            is Boolean -> gp.booleanOrNull == av
            else -> gp.content == av.toString()
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.double(): Double = this.content.toDouble()

    // ── (B) GAP-WORLD — the live-selection / downstream / long-sim dimensions BLOCKED on the un-banked world ──

    /**
     * GT3 dimension (a-SELECTION)/(b)/(c) require the scenario-1010 GAME-ENTITY ROWS (174 generals + 94
     * cities + 2 nations + diplomacy + nation_env) materialized into an `InMemoryTurnWorld` + the
     * `:app:game-engine` `AiTurnAdapter`. The GT1 golden NEVER banked those rows (the capture read them from
     * the live DB), and `:logic` has no engine adapter. So the live-selection replay cannot run faithfully
     * here — this is the GAP-WORLD cluster, NOT faked with an invented world. This test PINS the gap
     * (the golden carries the SELECTION targets but not
     * the world to reproduce them) so the harness is honest about what is and is not yet gated.
     */
    @Test
    fun `the live-selection dimension is blocked on the un-banked scenario-1010 world (GAP-WORLD)`() {
        // The golden carries the SELECTION targets (chosenActionCode/reason) but NOT the world rows the live
        // AI branched on. Assert the targets are PRESENT (so the GT2/engine-side gate can consume them) while
        // recording that they are not yet replayable in :logic.
        var withSelection = 0
        for (name in fixtureNames()) {
            val f = load(name)
            assertTrue(f["chosenActionCode"]!!.jsonPrimitive.content.startsWith("che_"), "$name has a RAW che_* selection")
            assertTrue(f["reason"]!!.jsonPrimitive.content.isNotEmpty(), "$name has a do<한글> reason")
            withSelection += 1
        }
        assertEquals(FIXTURE_COUNT, withSelection, "all 174 fixtures carry a selection target (consumed by the engine-side gate, not :logic)")
    }
}
