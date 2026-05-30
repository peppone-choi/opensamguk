package opensamguk.logic.tick

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Month-scoped RNG lineage — P3 AREA F5, Task FR1 (design §11 P3 (f); research §Cross-cutting #4, Units 1/4/5/9).
 *
 * Port target (PHP grand truth):
 *   - TurnExecutionHelper.php:461-466  $monthlyRng = RandUtil(LiteHashDRBG(simpleSerialize(hidden,'monthly',year,month)))  [4-tuple]
 *   - RandomizeCityTradeRate.php:18-23 simpleSerialize(hidden,'randomizeCityTradeRate',year,month)                         [4-tuple]
 *   - UpdateNationLevel.php:188-194    simpleSerialize(hidden,'nationLevelUp',year,month,nationID)                         [5-tuple]
 *   - UpdateNationLevel.php:205-212    simpleSerialize(hidden,'givenUnique',year,month,nationID,winnerID)                  [6-tuple]
 *
 * F5 REUSES the GREEN :common kernel (RandUtil / LiteHashDrbg / serializeSeed = Util::simpleSerialize) — NO new
 * RNG primitives. The `monthly` lineage MUST be DISTINCT from the per-general `preprocess`/`nationCommand`/
 * `generalCommand` lineages so the two draw streams never collide.
 *
 * simpleSerialize string length is mb_strlen (UTF-8 code points). The literals here are pure ASCII so length == char count:
 *   monthly=7 ; randomizeCityTradeRate=22 ; nationLevelUp=13 ; givenUnique=11.
 */
class MonthScopedRngTest {

    private val hidden = "00000000000000000000000000000000" // 32-char unit-test placeholder (G1b swaps the live config hex)

    // ---- the `monthly` seed byte-matches the PHP simpleSerialize 4-tuple token shape ----

    @Test
    fun `monthly seed byte matches simpleSerialize 4-tuple shape`() {
        val seed = MonthScopedRng.monthlySeed(hidden, 190, 3)
        assertEquals("str(32,$hidden)|str(7,monthly)|int(190)|int(3)", seed)
        assertEquals(4, seed.split("|").size)
        // identical to the literal PHP shape
        assertEquals(serializeSeed(hidden, "monthly", 190, 3), seed)
    }

    @Test
    fun `forMonth builds a RandUtil over the monthly seed`() {
        // the factory must produce the SAME draw stream as a hand-built RandUtil over the monthly seed.
        val expected = RandUtil(LiteHashDrbg(serializeSeed(hidden, "monthly", 190, 3)))
            .let { listOf(it.nextFloat1(), it.nextFloat1(), it.nextFloat1()) }
        val actualRng = MonthScopedRng.forMonth(hidden, 190, 3)
        val actual = listOf(actualRng.nextFloat1(), actualRng.nextFloat1(), actualRng.nextFloat1())
        assertEquals(expected, actual)
    }

    // ---- per-action sub-seed factories each byte-match their PHP tuple ----

    @Test
    fun `trade-rate sub-seed byte matches simpleSerialize 4-tuple shape`() {
        val seed = MonthScopedRng.tradeRateSeed(hidden, 190, 3)
        assertEquals("str(32,$hidden)|str(22,randomizeCityTradeRate)|int(190)|int(3)", seed)
        assertEquals(4, seed.split("|").size)
        assertEquals(serializeSeed(hidden, "randomizeCityTradeRate", 190, 3), seed)
    }

    @Test
    fun `nationLevelUp sub-seed byte matches simpleSerialize 5-tuple shape`() {
        val seed = MonthScopedRng.nationLevelUpSeed(hidden, 190, 3, 7)
        assertEquals("str(32,$hidden)|str(13,nationLevelUp)|int(190)|int(3)|int(7)", seed)
        assertEquals(5, seed.split("|").size)
        assertEquals(serializeSeed(hidden, "nationLevelUp", 190, 3, 7), seed)
    }

    @Test
    fun `givenUnique sub-seed byte matches simpleSerialize 6-tuple shape`() {
        val seed = MonthScopedRng.givenUniqueSeed(hidden, 190, 3, 7, 42)
        assertEquals("str(32,$hidden)|str(11,givenUnique)|int(190)|int(3)|int(7)|int(42)", seed)
        assertEquals(6, seed.split("|").size)
        assertEquals(serializeSeed(hidden, "givenUnique", 190, 3, 7, 42), seed)
    }

    // ---- the sub-seed factories return ready-to-draw RandUtils over the matching seed ----

    @Test
    fun `forTradeRate builds a RandUtil over the trade-rate sub-seed`() {
        val expected = RandUtil(LiteHashDrbg(MonthScopedRng.tradeRateSeed(hidden, 190, 3))).nextFloat1()
        assertEquals(expected, MonthScopedRng.forTradeRate(hidden, 190, 3).nextFloat1())
    }

    @Test
    fun `forNationLevelUp builds a RandUtil over the nationLevelUp sub-seed`() {
        val expected = RandUtil(LiteHashDrbg(MonthScopedRng.nationLevelUpSeed(hidden, 190, 3, 7))).nextFloat1()
        assertEquals(expected, MonthScopedRng.forNationLevelUp(hidden, 190, 3, 7).nextFloat1())
    }

    @Test
    fun `forGivenUnique builds a RandUtil over the givenUnique sub-seed`() {
        val expected = RandUtil(LiteHashDrbg(MonthScopedRng.givenUniqueSeed(hidden, 190, 3, 7, 42))).nextFloat1()
        assertEquals(expected, MonthScopedRng.forGivenUnique(hidden, 190, 3, 7, 42).nextFloat1())
    }

    // ---- DISTINCT lineage: the `monthly` seed must NOT collide with the per-general forks ----

    @Test
    fun `monthly lineage is distinct from the per-general generalCommand lineage`() {
        val monthly = MonthScopedRng.monthlySeed(hidden, 190, 3)
        // a per-general generalCommand seed at the SAME (year,month) — different component-2 literal + extra tokens.
        val generalCommand = serializeSeed(hidden, "generalCommand", 190, 3, 42, "che_농지개간")
        assertNotEquals(monthly, generalCommand)
        // and DISTINCT from the 5-tuple preprocess fork at the same (year,month) too.
        assertNotEquals(monthly, serializeSeed(hidden, "preprocess", 190, 3, 42))
        // the four month-scoped lineages are pairwise distinct as well.
        val seeds = listOf(
            monthly,
            MonthScopedRng.tradeRateSeed(hidden, 190, 3),
            MonthScopedRng.nationLevelUpSeed(hidden, 190, 3, 7),
            MonthScopedRng.givenUniqueSeed(hidden, 190, 3, 7, 42),
        )
        assertEquals(seeds.size, seeds.toSet().size, "month-scoped lineages must be pairwise distinct")
    }

    // ---- determinism: same inputs → same draw stream ----

    @Test
    fun `forMonth is deterministic across two independent factory calls`() {
        val a = MonthScopedRng.forMonth(hidden, 191, 7)
        val b = MonthScopedRng.forMonth(hidden, 191, 7)
        repeat(5) { assertEquals(a.nextFloat1(), b.nextFloat1()) }
    }

    @Test
    fun `distinct months yield distinct monthly seeds`() {
        assertNotEquals(MonthScopedRng.monthlySeed(hidden, 190, 3), MonthScopedRng.monthlySeed(hidden, 190, 4))
        assertNotEquals(MonthScopedRng.monthlySeed(hidden, 190, 3), MonthScopedRng.monthlySeed(hidden, 191, 3))
        assertTrue(MonthScopedRng.monthlySeed(hidden, 190, 3) == MonthScopedRng.monthlySeed(hidden, 190, 3))
    }
}
