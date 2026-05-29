package opensamguk.logic.domestic

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the domestic helpers (func_process.php:12-71, func_converter.php:906,
 * func_gamerule.php:942-952). The P1 pipeline is empty (identity), so onCalcStat is a no-op.
 * Exact float values are ALSO pinned by the AREA G PHP golden (G1) — this unit test is the guard.
 */
class DomesticHelpersTest {

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

    private fun rng(seed: String = "domesticHelpersSeed") = RandUtil(LiteHashDrbg(seed))

    // --- getDomesticExpLevelBonus: func_converter.php:906 -> 1 + expLevel/500 ---

    @Test
    fun `expLevelBonus 0 is 1`() = assertEquals(1.0, getDomesticExpLevelBonus(0))

    @Test
    fun `expLevelBonus 500 is 2`() = assertEquals(2.0, getDomesticExpLevelBonus(500))

    // --- criticalScoreEx: func_process.php:63-71. normal returns 1.0 with ZERO RNG draws ---

    @Test
    fun `criticalScoreEx normal returns 1 and draws zero from the rng`() {
        val a = rng()
        val b = rng()
        assertEquals(1.0, criticalScoreEx(a, "normal"))
        // a was NOT advanced by the "normal" path: its next draw must equal a fresh rng's first draw.
        assertEquals(b.nextFloat1(), a.nextFloat1(), "criticalScoreEx(normal) must not advance the rng stream")
    }

    @Test
    fun `criticalScoreEx success draws nextRange 2_2 to 3_0 and advances the rng`() {
        val a = rng()
        val b = rng()
        val v = criticalScoreEx(a, "success")
        assertEquals(b.nextRange(2.2, 3.0), v, "success must draw nextRange(2.2,3.0)")
        // positive control: the stream WAS advanced (diverges from a fresh rng's first draw)
        assertNotEquals(rng().nextFloat1(), a.nextFloat1())
    }

    @Test
    fun `criticalScoreEx fail draws nextRange 0_2 to 0_4`() {
        val a = rng()
        val b = rng()
        val v = criticalScoreEx(a, "fail")
        assertEquals(b.nextRange(0.2, 0.4), v, "fail must draw nextRange(0.2,0.4)")
    }

    // --- criticalRatioDomestic: func_process.php:12-50. Stats read with withInjury=FALSE. ---

    @Test
    fun `criticalRatioDomestic at avg over stat 1_0 matches the pow(1 over 1_2) reference`() {
        // raw L=100, S=I=80: cross-stat (str+=round(I/4), intel+=round(S/4)) -> reads 100/100/100,
        // so avg=100 and avg/stat=1.0 for every type -> ratio capped at 1.0 (the plan's reference case).
        val g = general(leadership = 100, strength = 80, intel = 80)
        val expectedFail = ((1.0 / 1.2).pow(1.4) - 0.3).coerceIn(0.0, 0.5)
        val expectedSuccess = ((1.0 / 1.2).pow(1.5) - 0.25).coerceIn(0.0, 0.5)
        for (type in listOf("leadership", "strength", "intel")) {
            val r = criticalRatioDomestic(g, type, pipeline)
            assertTrue(kotlin.math.abs(r.fail - expectedFail) < 1e-12, "$type fail=${r.fail} != $expectedFail")
            assertTrue(kotlin.math.abs(r.success - expectedSuccess) < 1e-12, "$type success=${r.success} != $expectedSuccess")
        }
        // sanity: this is exactly clamp(pow(1/1.2,1.5)-0.25) which saturates at 0.5
        assertEquals(0.5, expectedSuccess)
    }

    @Test
    fun `criticalRatioDomestic L S I 70 uses cross-stat-adjusted reads (faithful actual doubles)`() {
        // raw 70/70/70: cross-stat -> reads leadership=70, strength=88, intel=88; avg=82.
        val g = general(leadership = 70, strength = 70, intel = 70)

        // leadership: ratio = min(82/70, 1.2) = 1.1714285714285715
        val lr = criticalRatioDomestic(g, "leadership", pipeline)
        val lRatio = minOf(82.0 / 70.0, 1.2)
        assertTrue(kotlin.math.abs(lr.fail - ((lRatio / 1.2).pow(1.4) - 0.3).coerceIn(0.0, 0.5)) < 1e-12)
        assertTrue(kotlin.math.abs(lr.success - ((lRatio / 1.2).pow(1.5) - 0.25).coerceIn(0.0, 0.5)) < 1e-12)

        // strength & intel: stat read = 88, ratio = 82/88 = 0.9318181818181818
        val sRatio = minOf(82.0 / 88.0, 1.2)
        val expFail = ((sRatio / 1.2).pow(1.4) - 0.3).coerceIn(0.0, 0.5)
        val expSuccess = ((sRatio / 1.2).pow(1.5) - 0.25).coerceIn(0.0, 0.5)
        for (type in listOf("strength", "intel")) {
            val r = criticalRatioDomestic(g, type, pipeline)
            assertTrue(kotlin.math.abs(r.fail - expFail) < 1e-12, "$type fail=${r.fail} != $expFail")
            assertTrue(kotlin.math.abs(r.success - expSuccess) < 1e-12, "$type success=${r.success} != $expSuccess")
        }
    }

    // --- updateMaxDomesticCritical: func_gamerule.php:942-952 (aux only; inheritance is a P6 seam) ---

    @Test
    fun `updateMaxDomesticCritical adds score over 2 to the aux value`() {
        assertEquals(14.0, updateMaxDomesticCritical(10.0, 8))
    }
}
