package opensamguk.logic.ai

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-INSTANCE / Task FI2 — [AiInstanceState.calcGenType] parity tests.
 *
 * Port target = PHP `legacy/devsam-core/hwe/sammo/GeneralAI.php:175-204` (the FIRST draw site of the AI
 * decision). The near-balance band gate is the single biggest desync trap: its conditional `nextBool`
 * appears/disappears with the predicate (`weakerStat >= strongerStat * 0.8`), and its presence/absence
 * shifts EVERY later draw off the ONE shared `"GeneralAI"` stream. The exact `Double` float arg
 * (`intel/strength/2` or `strength/intel/2`) is itself a byte-parity target — `*0.8` and `/2` are Double
 * float division, NEVER integer division; the prob ∈ [0.4, 0.5).
 *
 * Discipline: getStatValue flavor `getX(false)` = `(false,true,true,true)` with `clamp(v,1.0)` (valueFit
 * min 1) on strength/intel only, leadership NO floor (decision #7, G8 — via the GREEN [AiSeed] flavor
 * table). `useFloor=true` already truncated — do NOT re-round. The `t무장=1 / t지장=2 / t통솔장=4` flags
 * are bitwise `|=`; `t통솔장` is non-RNG (a `minNPCWarLeadership` threshold).
 */
class CalcGenTypeTest {

    private val pipeline = GeneralActionPipeline()

    private fun general(leadership: Int, strength: Int, intel: Int, injury: Int = 0) = General(
        id = 1,
        nationId = 1,
        cityId = 1,
        leadership = leadership,
        strength = strength,
        intel = intel,
        injury = injury,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = 0,
        gold = 1000,
        rice = 1000,
    )

    private fun policy(minNPCWarLeadership: Int = 40) =
        AutorunNationPolicy(
            npcType = 3,
            tech = 0,
            develcost = 100,
            // The nation-layer `values` override sets minNPCWarLeadership (the merge path, :204-210).
            nationPolicy = mapOf("values" to mapOf("minNPCWarLeadership" to minNPCWarLeadership)),
        )

    /** A RandUtil that records every nextBool(prob) call (count + the exact Double arg) and returns false. */
    private class RecordingRng : RandUtil(LiteHashDrbg("recording-seed")) {
        val boolArgs = mutableListOf<Double>()
        override fun nextBool(prob: Double): Boolean {
            boolArgs.add(prob)
            return false // never flip the bit — we only assert the draw COUNT + the float arg here
        }
    }

    private fun state(general: General, policy: AutorunNationPolicy) = AiInstanceState(
        generalNationId = 1,
        env = AiEnv(year = 200, month = 1, startYear = 180, develCost = 100),
        nationPolicy = policy,
        nationRowLookup = { AiNationRow(nation = 1, level = 1, capital = 5, gold = 0, rice = 0) },
        nationStor = emptyMap(),
        diplomacyOf = { emptyList() },
        frontMaxOf = { 0 },
        kvRecorder = object : AiKvRecorder {
            override fun recordNationKv(nationId: Int, key: String, value: Any?) {}
        },
    )

    // -----------------------------------------------------------------------------------------------
    // (1) OUTSIDE the band → ZERO draws + genType = t무장 (strength >> intel).
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `strength dominates outside the band yields zero draws and t무장`() {
        // strength=100,intel=50 → flavors: strength=100+phpRound(50/4=12.5→13)=113;
        //   intel=50+phpRound(100/4=25)=75. 75 >= 113*0.8(90.4)? NO → no draw → pure 무장.
        // leadership 30 < minNPCWarLeadership 40 → NO 통솔장 flag.
        val g = general(leadership = 30, strength = 100, intel = 50)
        val rng = RecordingRng()
        val genType = state(g, policy()).calcGenType(rng, StatCalc(g, pipeline))
        assertTrue(rng.boolArgs.isEmpty(), "outside the band → ZERO nextBool draws")
        assertEquals(AiInstanceState.T_MUJANG, genType)
    }

    @Test
    fun `intel dominates outside the band yields zero draws and t지장`() {
        // strength=50,intel=100 → flavors: strength=50+phpRound(100/4=25)=75;
        //   intel=100+phpRound(50/4=12.5→13)=113. 75 >= 113? NO → 지장 branch;
        //   75 >= 113*0.8(90.4)? NO → no draw → pure 지장.
        val g = general(leadership = 30, strength = 50, intel = 100)
        val rng = RecordingRng()
        val genType = state(g, policy()).calcGenType(rng, StatCalc(g, pipeline))
        assertTrue(rng.boolArgs.isEmpty(), "outside the band → ZERO nextBool draws")
        assertEquals(AiInstanceState.T_JIJANG, genType)
    }

    // -----------------------------------------------------------------------------------------------
    // (2) INSIDE the band → exactly ONE nextBool with the EXACT Double float arg (bit-identical).
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `strength branch inside the band draws nextBool(intel over strength over 2) once`() {
        // Pick stats where the cross-stat blend lands on clean integers so the float arg is exact.
        // strength=80, intel=80, no cross blend possible (need raw)? Use leadership-only no-injury flavor:
        //   strength flavor = 80 + round(80/4)=80+20=100 ; intel flavor = 80 + round(80/4)=100.
        //   strength(100) >= intel(100) → 무장 branch; intel(100) >= strength*0.8(80) → DRAW.
        //   prob = intel/strength/2 = 100.0/100.0/2 = 0.5.
        val g = general(leadership = 30, strength = 80, intel = 80)
        val rng = RecordingRng()
        state(g, policy()).calcGenType(rng, StatCalc(g, pipeline))
        assertEquals(1, rng.boolArgs.size, "inside the band → exactly ONE nextBool")
        assertEquals(0.5, rng.boolArgs[0], "prob = intel.toDouble()/strength/2, Double float division")
    }

    @Test
    fun `intel branch inside the band draws nextBool(strength over intel over 2) once`() {
        // strength flavor < intel flavor → 지장 branch; strength >= intel*0.8 → DRAW strength/intel/2.
        // strength=70,intel=90: strength flavor = 70+round(90/4)=70+round(22.5 half-away=23)=93;
        //   intel flavor = 90+round(70/4)=90+round(17.5 half-away=18)=108→clamp(0,255)=108.
        //   93 < 108 → 지장 branch; 93 >= 108*0.8(86.4) → DRAW; prob = 93/108/2.
        val g = general(leadership = 30, strength = 70, intel = 90)
        val rng = RecordingRng()
        state(g, policy()).calcGenType(rng, StatCalc(g, pipeline))
        assertEquals(1, rng.boolArgs.size, "inside the band → exactly ONE nextBool")
        assertEquals(93.0 / 108.0 / 2.0, rng.boolArgs[0], "prob = strength.toDouble()/intel/2")
        assertTrue(rng.boolArgs[0] >= 0.4 && rng.boolArgs[0] < 0.5, "prob ∈ [0.4, 0.5)")
    }

    // -----------------------------------------------------------------------------------------------
    // (3) the draw COUNT shifts with the band predicate (presence/absence of the FIRST draw).
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `the draw count shifts with the near-balance band predicate`() {
        //   in-band : strength=80,intel=80 → flavors 100/100 → in band → 1 draw (above).
        //   out-band: strength=100,intel=50 → flavors 113/75 → 75>=113*0.8(90.4)? NO → 0 draws.
        val inBand = RecordingRng()
        val gIn = general(leadership = 30, strength = 80, intel = 80)
        state(gIn, policy()).calcGenType(inBand, StatCalc(gIn, pipeline))

        val outBand = RecordingRng()
        val gOut = general(leadership = 30, strength = 100, intel = 50)
        state(gOut, policy()).calcGenType(outBand, StatCalc(gOut, pipeline))

        assertEquals(1, inBand.boolArgs.size)
        assertEquals(0, outBand.boolArgs.size)
        assertTrue(inBand.boolArgs.size != outBand.boolArgs.size, "count shifts with the predicate")
    }

    // -----------------------------------------------------------------------------------------------
    // (4) tie strength == intel → 무장 branch (the `>=` comparator).
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `tie strength equals intel takes the 무장 branch`() {
        // strength flavor == intel flavor → `strength >= intel` true → 무장 branch (not 지장).
        // strength=80,intel=80 → both 100; intel>=strength*0.8 → DRAW; if it returns true, |= 지장.
        val g = general(leadership = 30, strength = 80, intel = 80)
        // Use a real GeneralAI-stream rng so the FIRST draw of the decision comes off cursor 0.
        val rng = AiSeed.rng("0123456789abcdef0123456789abcdef", year = 196, month = 1, generalId = 42)
        val genType = state(g, policy()).calcGenType(rng, StatCalc(g, pipeline))
        // The base flag is 무장; the conditional draw may OR in 지장 (whichever the real stream yields).
        assertTrue((genType and AiInstanceState.T_MUJANG) != 0, "tie → 무장 base flag set")
    }

    // -----------------------------------------------------------------------------------------------
    // (5) the t통솔장 flag is non-RNG (leadership >= minNPCWarLeadership), set via bitwise |=.
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `t통솔장 flag is non-RNG and ORed in when leadership meets the threshold`() {
        // leadership 50 >= minNPCWarLeadership 40 → |= t통솔장 (no draw for this flag).
        // strength=100,intel=50 outside band → 0 conditional draws; the 통솔장 flag still ORs in.
        val g = general(leadership = 50, strength = 100, intel = 50)
        val rng = RecordingRng()
        val genType = state(g, policy(minNPCWarLeadership = 40)).calcGenType(rng, StatCalc(g, pipeline))
        assertTrue(rng.boolArgs.isEmpty(), "the 통솔장 flag consumes NO draw")
        assertEquals(AiInstanceState.T_MUJANG or AiInstanceState.T_TONGSOLJANG, genType)

        // below the threshold → NO 통솔장 flag.
        val g2 = general(leadership = 39, strength = 100, intel = 50)
        val genType2 = state(g2, policy(minNPCWarLeadership = 40)).calcGenType(RecordingRng(), StatCalc(g2, pipeline))
        assertEquals(0, genType2 and AiInstanceState.T_TONGSOLJANG, "below threshold → no 통솔장")
    }

    // -----------------------------------------------------------------------------------------------
    // (6) the FIRST draw of the decision comes off the "GeneralAI" stream (cursor at 0 before).
    // -----------------------------------------------------------------------------------------------

    @Test
    fun `the band draw advances the GeneralAI stream cursor from zero`() {
        val hidden = "0123456789abcdef0123456789abcdef"
        // in-band general → exactly one draw → the cursor must advance vs a pristine stream.
        val g = general(leadership = 30, strength = 80, intel = 80)
        val drawing = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        state(g, policy()).calcGenType(drawing, StatCalc(g, pipeline))
        // The very next draw off the drawing stream must NOT equal the FIRST draw of a pristine stream
        // (the band nextBool already consumed cursor position 0).
        val pristine = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        assertTrue(
            drawing.nextFloat1() != pristine.nextFloat1(),
            "the band nextBool consumed the FIRST draw → the stream cursor advanced off 0",
        )

        // out-of-band general → ZERO draws → the cursor stays at 0 (first draw still pristine-equal).
        val gOut = general(leadership = 30, strength = 100, intel = 50)
        val untouched = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        state(gOut, policy()).calcGenType(untouched, StatCalc(gOut, pipeline))
        val pristine2 = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        assertEquals(
            pristine2.nextFloat1(), untouched.nextFloat1(),
            "out-of-band → ZERO draws → cursor still at 0",
        )
    }
}
