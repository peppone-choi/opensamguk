package opensamguk.logic.ai

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.ai.families.GenFoundFamily
import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FQ1 — the `ORDER BY RAND()` quarantine substitute (decision #6, G4, R-GATE §1).
 *
 * GRAND TRUTH = PHP `GeneralAI.php:3324` (`do선양`) + `:3345` (`do국가선택`-오랑캐). Both are MySQL/MariaDB-side
 * `ORDER BY RAND()` row-picks — they draw **ZERO bytes** off the `RandUtil(LiteHashDrbg)` DRBG stream, so the
 * AI's rng cursor (`stateIdx`/`bufferIdx`) is UNAFFECTED (G4 §G truth: "DRBG stream position is unaffected").
 * The only non-determinism is *which row id is chosen among ties*; the safe faithful substitute (CLAUDE.md
 * parity rule #5) is a deterministic `min(no)` / `min(nation)` over the SAME WHERE-filtered candidate set
 * (insertion = general.no order, G13), proven 0-draw + valid-member.
 *
 * Reachability: scenario 1010 has **0 npc==5 and 0 npc==9** generals of 678 (R-GATE §1); `can선양` requires
 * npc==5, 오랑캐임관 requires npc==9 → NEITHER path fires in the gate. This test exercises the substitute on a
 * CRAFTED npc==5 ruler fixture (the sibling-byte-match proof of G4 §4) — the picked id is asserted only to be
 * a VALID member of the candidate set + the substitute is asserted to consume ZERO DRBG draws. DO NOT fabricate
 * an id, weaken a test, or seed npc 5/9 into 1010.
 */
class QuarantineSubstituteTest {

    /**
     * A draw-recording RandUtil over a REAL [LiteHashDrbg]: every overridable draw method increments
     * [drawCount] (and still consumes the real draw, so the cursor would advance IF anything drew). The
     * [drbg] is held so the test can peek the byte-exact cursor (stateIdx/bufferIdx) BEFORE/AFTER the pick —
     * the assertion is BOTH "no draw method was called" AND "the DRBG cursor is byte-identical".
     */
    private class RecordingRng(val drbg: LiteHashDrbg) : RandUtil(drbg) {
        var drawCount = 0
        override fun nextFloat1(): Double { drawCount++; return super.nextFloat1() }
        override fun nextRange(min: Double, max: Double): Double { drawCount++; return super.nextRange(min, max) }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int { drawCount++; return super.nextRangeInt(minInclusive, maxInclusive) }
        override fun nextInt(minInclusive: Int, maxExclusive: Int): Int { drawCount++; return super.nextInt(minInclusive, maxExclusive) }
        override fun nextBit(): Boolean { drawCount++; return super.nextBit() }
        override fun nextBool(prob: Double): Boolean { drawCount++; return super.nextBool(prob) }
        override fun <T> choice(items: List<T>): T { drawCount++; return super.choice(items) }
    }

    private fun general(id: Int, nationId: Int, officerLevel: Int = 1, npcType: Int = 2): General =
        General(
            id = id,
            nationId = nationId,
            cityId = 0,
            leadership = 50,
            strength = 50,
            intel = 50,
            injury = 0,
            experience = 0.0,
            dedication = 0.0,
            officerLevel = officerLevel,
            gold = 0,
            rice = 0,
            npcType = npcType,
        )

    // --------------------------------------------------------------------------------------------------
    // (1) do선양 substitute — SELECT `no` FROM general WHERE nation=%i AND npc!=5 ORDER BY RAND() → min(no)
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `do선양 substitute picks min(no) over nation members with npc != 5 and draws nothing`() {
        // Candidate set order = general.no insertion order (G13); insertion is intentionally NOT sorted
        // ascending to prove the substitute selects min(no), not first-inserted.
        val candidates = listOf(
            general(id = 30, nationId = 1, officerLevel = 12, npcType = 5),  // ruler self (npc==5) — EXCLUDED by npc!=5
            general(id = 17, nationId = 1, npcType = 2),                     // own nation, npc!=5 → candidate
            general(id = 9, nationId = 1, npcType = 2),                      // own nation, npc!=5 → candidate (min no)
            general(id = 5, nationId = 2, npcType = 2),                      // other nation → EXCLUDED
            general(id = 21, nationId = 1, npcType = 5),                     // own nation but npc==5 → EXCLUDED
        )
        val rng = RecordingRng(LiteHashDrbg("substitute-seed"))
        val s0 = rng.drbg.peekStateIdx(); val b0 = rng.drbg.peekBufferIdx()

        val picked = GenFoundFamily.seonyangDestGeneralId(nationId = 1, candidates = candidates, rng = rng)

        // min(no) over {17,9} = 9 (NOT 17 the first-inserted, NOT 30 the npc==5 self)
        assertEquals(9, picked, "do선양 substitute = min(no) over nation==1 & npc!=5 candidates")
        assertEquals(0, rng.drawCount, "ORDER BY RAND() substitute consumes ZERO DRBG draws")
        assertEquals(s0, rng.drbg.peekStateIdx(), "DRBG stateIdx cursor unchanged across the pick")
        assertEquals(b0, rng.drbg.peekBufferIdx(), "DRBG bufferIdx cursor unchanged across the pick")
    }

    @Test
    fun `do선양 substitute returns null when no candidate matches (empty WHERE set)`() {
        // PHP queryFirstField returns null (no row) — substitute mirrors with null (no-fabrication).
        val candidates = listOf(
            general(id = 9, nationId = 2, npcType = 2),   // other nation
            general(id = 7, nationId = 1, npcType = 5),   // own nation but npc==5
        )
        val rng = RecordingRng(LiteHashDrbg("substitute-seed"))
        val picked = GenFoundFamily.seonyangDestGeneralId(nationId = 1, candidates = candidates, rng = rng)
        assertNull(picked, "no nation==1 & npc!=5 candidate → null (PHP queryFirstField null)")
        assertEquals(0, rng.drawCount, "no draw even on the empty-candidate path")
    }

    @Test
    fun `do선양 substitute on a crafted npc==5 ruler fixture picks a VALID member of the candidate set`() {
        // The G4 §4 sibling-byte-match proof: a crafted npc==5 officer_level==12 ruler nation. The substitute
        // picks SOME id; we assert ONLY that it is a valid member of the WHERE set (the ORDER BY RAND id is
        // bounded to "any valid candidate", never byte-equal to PHP's random pick).
        val members = listOf(
            general(id = 12, nationId = 3, officerLevel = 12, npcType = 5),  // the npc==5 ruler — EXCLUDED (npc!=5)
            general(id = 40, nationId = 3, npcType = 2),
            general(id = 11, nationId = 3, npcType = 3),
            general(id = 88, nationId = 3, npcType = 2),
        )
        val validSet = setOf(40, 11, 88) // nation==3 & npc!=5
        val rng = RecordingRng(LiteHashDrbg("substitute-seed"))
        val picked = GenFoundFamily.seonyangDestGeneralId(nationId = 3, candidates = members, rng = rng)
        assertTrue(picked != null && picked in validSet, "picked id is a valid member of the WHERE set")
        assertEquals(11, picked, "deterministic min(no) over {40,11,88} = 11")
        assertEquals(0, rng.drawCount, "crafted fixture pick is still 0-draw")
    }

    // --------------------------------------------------------------------------------------------------
    // (2) 오랑캐임관 substitute — SELECT nation FROM general WHERE officer_level=12 AND npc=9 AND nation
    //     ORDER BY RAND() → the `nation` of the min(no) matching row (G4 §4: "min(nation)").
    // --------------------------------------------------------------------------------------------------

    @Test
    fun `오랑캐임관 substitute picks the nation of the min(no) npc==9 ruler and draws nothing`() {
        val candidates = listOf(
            general(id = 50, nationId = 7, officerLevel = 12, npcType = 9),  // ruler nation 7
            general(id = 14, nationId = 4, officerLevel = 12, npcType = 9),  // ruler nation 4 (min no)
            general(id = 3, nationId = 0, officerLevel = 12, npcType = 9),   // nation==0 → EXCLUDED (`AND nation` falsy guard)
            general(id = 8, nationId = 9, officerLevel = 5, npcType = 9),    // officer_level!=12 → EXCLUDED
            general(id = 6, nationId = 9, officerLevel = 12, npcType = 2),   // npc!=9 → EXCLUDED
        )
        val rng = RecordingRng(LiteHashDrbg("substitute-seed"))
        val s0 = rng.drbg.peekStateIdx(); val b0 = rng.drbg.peekBufferIdx()

        val rulerNation = GenFoundFamily.orankaeRulerNation(candidates = candidates, rng = rng)

        // min(no) over {50(nation7),14(nation4)} = id 14 → its projected `nation` = 4
        assertEquals(4, rulerNation, "오랑캐임관 substitute = nation of the min(no) officer_level==12 & npc==9 & nation!=0 row")
        assertEquals(0, rng.drawCount, "ORDER BY RAND() substitute consumes ZERO DRBG draws")
        assertEquals(s0, rng.drbg.peekStateIdx(), "DRBG stateIdx cursor unchanged across the pick")
        assertEquals(b0, rng.drbg.peekBufferIdx(), "DRBG bufferIdx cursor unchanged across the pick")
    }

    @Test
    fun `오랑캐임관 substitute returns null when no 오랑캐-ruled nation exists`() {
        val candidates = listOf(
            general(id = 3, nationId = 0, officerLevel = 12, npcType = 9),   // nation==0 → excluded
            general(id = 6, nationId = 9, officerLevel = 12, npcType = 2),   // npc!=9 → excluded
        )
        val rng = RecordingRng(LiteHashDrbg("substitute-seed"))
        val rulerNation = GenFoundFamily.orankaeRulerNation(candidates = candidates, rng = rng)
        assertNull(rulerNation, "no officer_level==12 & npc==9 & nation!=0 row → null (PHP queryFirstField null)")
        assertEquals(0, rng.drawCount, "no draw even on the empty-candidate path")
    }
}
