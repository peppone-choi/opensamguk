package opensamguk.logic.traits

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Port-faithful tests for the 8 domestic specialties (TD1) — PHP `ActionSpecialDomestic/` (`che_*.php`).
 *
 * Grand truth:
 *   - 6/8 uniform (`che_경작.php:18-26` and the identical 상재/발명/축성/수비/통찰):
 *       matching turnType → score×1.1, cost×0.8, success+0.1; else identity.
 *   - `che_인덕.php`: same uniform buff, two-key gate (민심 OR 인구).
 *   - `che_귀모.php`: outlier — 계략 && success → +0.2 only.
 *   - None(id 0) / che_거상(id 999): no onCalcDomestic override → identity → registry resolves null.
 *   - ZERO RNG: the hooks never consume from the DRBG.
 */
class SpecialDomesticTest {

    private fun general() = General(
        id = 1, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 1000, rice = 1000,
    )

    private fun GeneralActionModule.score(actionKey: String, v: Double = 100.0) =
        onCalcDomestic(general(), actionKey, "score", v)
    private fun GeneralActionModule.cost(actionKey: String, v: Double = 100.0) =
        onCalcDomestic(general(), actionKey, "cost", v)
    private fun GeneralActionModule.success(actionKey: String, v: Double = 0.5) =
        onCalcDomestic(general(), actionKey, "success", v)
    private fun GeneralActionModule.fail(actionKey: String, v: Double = 0.5) =
        onCalcDomestic(general(), actionKey, "fail", v)

    // --- The turnType ↔ specialty table (농업→경작, 상업→상재, 기술→발명, 성벽→축성, 수비→수비, 치안→통찰). ---
    private val uniformPairs = listOf(
        CheCultivate to "농업",
        CheCommerce to "상업",
        CheInvent to "기술",
        CheFortify to "성벽",
        CheDefend to "수비",
        CheInsight to "치안",
    )

    @Test
    fun `each uniform specialty buffs ONLY its matching turnType`() {
        for ((special, key) in uniformPairs) {
            // score ×1.1, cost ×0.8, success +0.1 on the matching key
            assertEquals(100.0 * 1.1, special.score(key), 1e-12, "${special.specialName} score $key")
            assertEquals(100.0 * 0.8, special.cost(key), 1e-12, "${special.specialName} cost $key")
            assertEquals(0.5 + 0.1, special.success(key), 1e-12, "${special.specialName} success $key")
            // 'fail' varType is identity even on the matching key (PHP only branches score/cost/success)
            assertEquals(0.5, special.fail(key), 1e-12, "${special.specialName} fail $key identity")
        }
    }

    @Test
    fun `each uniform specialty is identity on every NON-matching turnType`() {
        val allKeys = listOf("농업", "상업", "기술", "성벽", "수비", "치안", "민심", "인구", "계략")
        for ((special, key) in uniformPairs) {
            for (other in allKeys.filter { it != key }) {
                assertEquals(100.0, special.score(other), 1e-12, "${special.specialName} score $other identity")
                assertEquals(100.0, special.cost(other), 1e-12, "${special.specialName} cost $other identity")
                assertEquals(0.5, special.success(other), 1e-12, "${special.specialName} success $other identity")
            }
        }
    }

    @Test
    fun `통찰 gates 치안 not 수비 (distinct from 수비 specialty)`() {
        // 통찰 is STAT_STRENGTH-typed yet gates 치안; 수비 gates 수비. They must NOT overlap.
        assertEquals(100.0 * 1.1, CheInsight.score("치안"), 1e-12)
        assertEquals(100.0, CheInsight.score("수비"), 1e-12)
        assertEquals(100.0 * 1.1, CheDefend.score("수비"), 1e-12)
        assertEquals(100.0, CheDefend.score("치안"), 1e-12)
    }

    @Test
    fun `인덕 buffs BOTH 민심 and 인구 with the uniform magnitudes`() {
        for (key in listOf("민심", "인구")) {
            assertEquals(100.0 * 1.1, CheVirtue.score(key), 1e-12, "인덕 score $key")
            assertEquals(100.0 * 0.8, CheVirtue.cost(key), 1e-12, "인덕 cost $key")
            assertEquals(0.5 + 0.1, CheVirtue.success(key), 1e-12, "인덕 success $key")
        }
        // identity everywhere else (e.g. 농업, 계략)
        assertEquals(100.0, CheVirtue.score("농업"), 1e-12)
        assertEquals(100.0, CheVirtue.score("계략"), 1e-12)
    }

    @Test
    fun `귀모 adds +0_2 ONLY on 계략 success — no score or cost effect`() {
        assertEquals(0.5 + 0.2, CheScheme.success("계략"), 1e-12)
        // score / cost on 계략 are identity (귀모 only branches success)
        assertEquals(100.0, CheScheme.score("계략"), 1e-12)
        assertEquals(100.0, CheScheme.cost("계략"), 1e-12)
        // success on any other turnType is identity
        assertEquals(0.5, CheScheme.success("농업"), 1e-12)
        assertEquals(0.5, CheScheme.success("민심"), 1e-12)
    }

    @Test
    fun `귀모 magnitude is +0_2 NOT the uniform +0_1`() {
        // guards against accidentally folding 귀모 into the uniform family
        assertEquals(0.2, CheScheme.success("계략") - 0.5, 1e-12)
    }

    @Test
    fun `registry resolves the 8 effective codes to their modules`() {
        assertSame(CheCultivate, SpecialDomesticRegistry.resolve("che_경작"))
        assertSame(CheCommerce, SpecialDomesticRegistry.resolve("che_상재"))
        assertSame(CheInvent, SpecialDomesticRegistry.resolve("che_발명"))
        assertSame(CheFortify, SpecialDomesticRegistry.resolve("che_축성"))
        assertSame(CheDefend, SpecialDomesticRegistry.resolve("che_수비"))
        assertSame(CheInsight, SpecialDomesticRegistry.resolve("che_통찰"))
        assertSame(CheVirtue, SpecialDomesticRegistry.resolve("che_인덕"))
        assertSame(CheScheme, SpecialDomesticRegistry.resolve("che_귀모"))
        // exactly 8 effective members
        val codes = listOf("che_경작", "che_상재", "che_발명", "che_축성", "che_수비", "che_통찰", "che_인덕", "che_귀모")
        assertEquals(8, codes.size)
        codes.forEach { assertNotNull(SpecialDomesticRegistry.resolve(it), "$it resolves") }
    }

    @Test
    fun `None and che_거상 and blank are inert (resolve null)`() {
        assertNull(SpecialDomesticRegistry.resolve("None"))
        assertNull(SpecialDomesticRegistry.resolve(null))
        assertNull(SpecialDomesticRegistry.resolve(""))
        // che_거상 (id 999) defines no onCalcDomestic override in PHP → identity → skipped slot
        assertNull(SpecialDomesticRegistry.resolve("che_거상"))
    }

    @Test
    fun `unknown code resolves null (never throws)`() {
        assertNull(SpecialDomesticRegistry.resolve("che_does_not_exist"))
    }

    /** Counts every DRBG block generation so the test can prove the hooks consume ZERO randomness. */
    private class CountingDrbg(seed: String) : LiteHashDrbg(seed) {
        var blocks = 0
        override fun genNextBlock() {
            blocks++
            super.genNextBlock()
        }
    }

    @Test
    fun `domestic hooks consume ZERO RNG (draw count unchanged)`() {
        val drbg = CountingDrbg("td1-zero-rng")
        val rand = RandUtil(drbg)
        // baseline: one draw to establish the stream is live and the counter moves under real draws
        rand.nextFloat1()
        val baselineBlocks = drbg.blocks

        // exercise EVERY hook on EVERY specialty across all turnTypes/varTypes
        val all: List<GeneralActionModule> =
            uniformPairs.map { it.first } + listOf(CheVirtue, CheScheme)
        val keys = listOf("농업", "상업", "기술", "성벽", "수비", "치안", "민심", "인구", "계략")
        val varTypes = listOf("score", "cost", "success", "fail")
        for (mod in all) {
            for (key in keys) {
                for (vt in varTypes) {
                    mod.onCalcDomestic(general(), key, vt, 100.0)
                }
            }
        }

        // no module touched the DRBG → block count is exactly the baseline
        assertEquals(baselineBlocks, drbg.blocks, "domestic specialty hooks must not draw RNG")

        // and the stream continuation is byte-identical to an untouched parallel RNG
        val expectedNext = RandUtil(LiteHashDrbg("td1-zero-rng")).let { it.nextFloat1(); it.nextFloat1() }
        assertEquals(expectedNext, rand.nextFloat1(), 0.0)
    }

    @Test
    fun `onCalcStat onCalcStrategic onCalcNationalIncome are identity (domestic overrides ONLY onCalcDomestic)`() {
        val all: List<GeneralActionModule> =
            uniformPairs.map { it.first } + listOf(CheVirtue, CheScheme)
        for (mod in all) {
            assertEquals(70.0, mod.onCalcStat(general(), "leadership", 70.0), 1e-12)
            assertEquals(60.0, mod.onCalcStat(general(), "strength", 60.0), 1e-12)
            assertEquals(5.0, mod.onCalcStrategic(general(), "delay", 5.0), 1e-12)
            assertEquals(100.0, mod.onCalcNationalIncome(general(), "gold", 100.0), 1e-12)
            assertEquals(60.0, mod.onCalcOpposeStat(general(), "strength", 60.0), 1e-12)
        }
    }
}
