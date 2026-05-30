package opensamguk.logic.ai

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.stats.StatCalc
import opensamguk.logic.util.clamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * FS1 — [AiSeed] parity tests.
 *
 * Port target = PHP `GeneralAI.php:153-159` (the per-general AI decision seed) +
 * `General.php:405-418`/`359-403` (the getStatValue flavors, G8). The seed is the 5-component,
 * `hiddenSeed`-DIRECT lineage `serializeSeed(hiddenSeed, "GeneralAI", year, month, generalId)` — NOT the
 * TS `buildSeedBase(world)` (decision #1). ONE `RandUtil` per general per DECISION, built once, threaded
 * by reference, never re-seeded; the `"GeneralAI"` token is a SEPARATE stream from execution's
 * `"nationCommand"`/`"generalCommand"` streams.
 *
 * The getStatValue flavor table (decision #7, G8): calcGenType = (false,true,true,true)+clamp(v,1.0) on
 * strength/intel (leadership no floor); reward = (false,true,true,true); promotion = (false,false,false,false).
 * useFloor=true already truncates — do NOT re-round.
 */
class AiSeedTest {

    private val hidden = "0123456789abcdef0123456789abcdef"

    // --- (1) the seed string byte-matches serializeSeed(hidden,"GeneralAI",y,m,id) ---

    @Test
    fun `seed string byte-matches the 5-component GeneralAI lineage`() {
        val expected = serializeSeed(hidden, "GeneralAI", 196, 1, 42)
        assertEquals(expected, AiSeed.seed(hidden, year = 196, month = 1, generalId = 42))
        // simpleSerialize: str(len,val) | str(len,val) | int | int | int
        assertEquals(
            "str(32,$hidden)|str(9,GeneralAI)|int(196)|int(1)|int(42)",
            AiSeed.seed(hidden, year = 196, month = 1, generalId = 42),
        )
    }

    // --- (2) determinism: same (hidden,y,m,id) ⇒ identical draw stream ---

    @Test
    fun `rng is deterministic across two builds for the same components`() {
        val a = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        val b = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        val drawsA = (1..8).map { a.nextFloat1() }
        val drawsB = (1..8).map { b.nextFloat1() }
        assertEquals(drawsA, drawsB)
    }

    // --- (3) the "GeneralAI" lineage differs from a "generalCommand" lineage for the same (y,m,id) ---

    @Test
    fun `GeneralAI stream is distinct from a generalCommand stream`() {
        val ai = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        // execution's per-command stream uses a DIFFERENT token; same other components.
        val exec = RandUtil(LiteHashDrbg(serializeSeed(hidden, "generalCommand", 196, 1, 42)))
        val aiDraws = (1..8).map { ai.nextFloat1() }
        val execDraws = (1..8).map { exec.nextFloat1() }
        assertNotEquals(aiDraws, execDraws)
    }

    @Test
    fun `different generalId yields a different stream`() {
        val a = AiSeed.rng(hidden, year = 196, month = 1, generalId = 42)
        val b = AiSeed.rng(hidden, year = 196, month = 1, generalId = 43)
        assertNotEquals((1..8).map { a.nextFloat1() }, (1..8).map { b.nextFloat1() })
    }

    // --- (4) the 3 flavor signatures over the GREEN StatCalc (no double-round) ---

    private fun general(intel: Int, strength: Int, leadership: Int, injury: Int) = General(
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

    private val pipeline = GeneralActionPipeline()

    @Test
    fun `calcGenType flavor is no-injury floored with valueFit min 1 on str and intel`() {
        // injury 20 must be IGNORED (withInjury=false). intel=90,strength=40:
        //   strength = 40 + round(90/4)=40+23(round(22.5)->23 half-away)=63 -> truncate 63
        //   intel    = 90 + round(40/4)=90+10=100 -> truncate 100
        //   leadership has NO valueFit floor and NO cross-stat blend = 50.
        val g = general(intel = 90, strength = 40, leadership = 50, injury = 20)
        val sc = StatCalc(g, pipeline)
        val leadership = AiSeed.genTypeLeadership(sc)
        val strength = AiSeed.genTypeStrength(sc)
        val intel = AiSeed.genTypeIntel(sc)
        assertEquals(50.0, leadership)
        assertEquals(63.0, strength)
        assertEquals(100.0, intel)
    }

    @Test
    fun `calcGenType valueFit clamps a zero-ish stat up to 1`() {
        // strength=0,intel=0 (and zero cross) ⇒ raw 0 ⇒ valueFit(0,1)=1 for both, leadership untouched.
        val g = general(intel = 0, strength = 0, leadership = 0, injury = 0)
        val sc = StatCalc(g, pipeline)
        assertEquals(1.0, AiSeed.genTypeStrength(sc))
        assertEquals(1.0, AiSeed.genTypeIntel(sc))
        assertEquals(0.0, AiSeed.genTypeLeadership(sc)) // leadership NOT clamped to 1
    }

    @Test
    fun `reward flavor is no-injury floored without valueFit`() {
        // (false,true,true,true) on strength: same 63 as calcGenType but NO valueFit-1 (irrelevant here).
        val g = general(intel = 90, strength = 40, leadership = 50, injury = 20)
        val sc = StatCalc(g, pipeline)
        assertEquals(63.0, AiSeed.rewardStat(sc, "strength"))
        assertEquals(100.0, AiSeed.rewardStat(sc, "intel"))
    }

    @Test
    fun `promotion flavor disables iActionObj and statAdjust`() {
        // (false,false,false,false): no cross-stat blend, no injury, no iActionObj.
        //   strength = raw 40 (no +round(intel/4)); intel = raw 90.
        val g = general(intel = 90, strength = 40, leadership = 50, injury = 20)
        val sc = StatCalc(g, pipeline)
        assertEquals(40.0, AiSeed.promotionStat(sc, "strength"))
        assertEquals(90.0, AiSeed.promotionStat(sc, "intel"))
        assertEquals(50.0, AiSeed.promotionStat(sc, "leadership"))
    }

    @Test
    fun `flavor results are integral doubles - useFloor already truncated, no double-round`() {
        // strength=45,intel=90,injury=10 would be 60.5 unfloored; floored flavor must be exactly 60.0.
        val g = general(intel = 90, strength = 45, leadership = 50, injury = 10)
        val sc = StatCalc(g, pipeline)
        // injury IGNORED (false) ⇒ strength = 45 + round(90/4)=45+round(22.5)=45+23=68 truncate 68
        val v = AiSeed.genTypeStrength(sc)
        assertEquals(68.0, v)
        assertTrue(v == kotlin.math.truncate(v)) // integral
    }
}
