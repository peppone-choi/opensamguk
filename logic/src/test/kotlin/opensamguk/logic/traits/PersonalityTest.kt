package opensamguk.logic.traits

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the 12 personality modules (Task TP1).
 *
 * PHP grand truth = the `ActionPersonality` package (source #5 of the 9-source stack, `General.php:117`
 * `new {personality}`). Each implements `iAction` + `use DefaultAction`. Hooks touched:
 *   - onCalcStat(statName ∈ experience/dedication/bonusTrain/bonusAtmos)
 *   - onCalcDomestic(turnType ∈ 징병/모병/단련, varType=cost|success)
 * Everything else folds identity. `getInfo()` returns the literal `$info` string verbatim.
 *
 * Magnitude table (transcribed from PHP):
 *   왕좌(0)  exp*1.1, bonusAtmos-5
 *   대의(1)  exp*1.1, bonusTrain-5
 *   의협(2)  bonusAtmos+5 ; 징/모병 cost*1.2
 *   패권(3)  bonusTrain+5 ; 징/모병 cost*1.2
 *   정복(4)  exp*0.9, bonusAtmos+5
 *   할거(5)  exp*0.9, bonusTrain+5
 *   출세(6)  exp*1.1     ; 징/모병 cost*1.2
 *   재간(7)  exp*0.9     ; 징/모병 cost*0.8
 *   유지(8)  bonusTrain-5; 징/모병 cost*0.8
 *   안전(9)  bonusAtmos-5; 징/모병 cost*0.8
 *   은둔(10) exp*0.9, dedication*0.9, bonusAtmos-5, bonusTrain-5 ; 단련 success+0.1 (ADDITIVE)
 *   None(-1) identity, name '-', info ''
 */
class PersonalityTest {

    private val registry = PersonalityRegistry()

    private fun general() = General(
        id = 1, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 1000, rice = 1000,
    )

    private fun mod(code: String): GeneralActionModule = registry.resolve(code)!!

    private fun stat(code: String, statName: String, v: Double): Double =
        mod(code).onCalcStat(general(), statName, v)

    private fun dom(code: String, turnType: String, varType: String, v: Double): Double =
        mod(code).onCalcDomestic(general(), turnType, varType, v)

    // --- registry resolution ---

    @Test
    fun `registry resolves the 11 che codes plus skips None blank null`() {
        for (code in listOf(
            "che_왕좌", "che_대의", "che_의협", "che_패권", "che_정복", "che_할거",
            "che_출세", "che_재간", "che_유지", "che_안전", "che_은둔",
        )) {
            assertTrue(registry.resolve(code) is GeneralActionModule, "expected module for $code")
        }
        assertNull(registry.resolve("None"))
        assertNull(registry.resolve(null))
        assertNull(registry.resolve(""))
        assertNull(registry.resolve("che_없는성격"))
    }

    // --- onCalcStat magnitudes (multiplicative experience/dedication) ---

    @Test
    fun `experience multipliers`() {
        // +10% group
        assertEquals(100.0 * 1.1, stat("che_왕좌", "experience", 100.0))
        assertEquals(100.0 * 1.1, stat("che_대의", "experience", 100.0))
        assertEquals(100.0 * 1.1, stat("che_출세", "experience", 100.0))
        // -10% group
        assertEquals(100.0 * 0.9, stat("che_정복", "experience", 100.0))
        assertEquals(100.0 * 0.9, stat("che_할거", "experience", 100.0))
        assertEquals(100.0 * 0.9, stat("che_재간", "experience", 100.0))
        assertEquals(100.0 * 0.9, stat("che_은둔", "experience", 100.0))
        // untouched
        assertEquals(100.0, stat("che_의협", "experience", 100.0))
        assertEquals(100.0, stat("che_패권", "experience", 100.0))
        assertEquals(100.0, stat("che_유지", "experience", 100.0))
        assertEquals(100.0, stat("che_안전", "experience", 100.0))
    }

    @Test
    fun `dedication multiplier is unique to 은둔`() {
        assertEquals(100.0 * 0.9, stat("che_은둔", "dedication", 100.0))
        // every other personality leaves dedication identity
        for (code in listOf(
            "che_왕좌", "che_대의", "che_의협", "che_패권", "che_정복", "che_할거",
            "che_출세", "che_재간", "che_유지", "che_안전",
        )) {
            assertEquals(100.0, stat(code, "dedication", 100.0), "dedication must be identity for $code")
        }
    }

    // --- bonusTrain / bonusAtmos ±5 (these flow to WAR-init only; consumer is war-side) ---

    @Test
    fun `bonusTrain plus minus 5`() {
        assertEquals(10.0 + 5.0, stat("che_패권", "bonusTrain", 10.0))
        assertEquals(10.0 + 5.0, stat("che_할거", "bonusTrain", 10.0))
        assertEquals(10.0 - 5.0, stat("che_대의", "bonusTrain", 10.0))
        assertEquals(10.0 - 5.0, stat("che_유지", "bonusTrain", 10.0))
        assertEquals(10.0 - 5.0, stat("che_은둔", "bonusTrain", 10.0))
        // 왕좌 only touches bonusAtmos, not bonusTrain
        assertEquals(10.0, stat("che_왕좌", "bonusTrain", 10.0))
    }

    @Test
    fun `bonusAtmos plus minus 5`() {
        assertEquals(10.0 + 5.0, stat("che_의협", "bonusAtmos", 10.0))
        assertEquals(10.0 + 5.0, stat("che_정복", "bonusAtmos", 10.0))
        assertEquals(10.0 - 5.0, stat("che_왕좌", "bonusAtmos", 10.0))
        assertEquals(10.0 - 5.0, stat("che_안전", "bonusAtmos", 10.0))
        assertEquals(10.0 - 5.0, stat("che_은둔", "bonusAtmos", 10.0))
        // 대의 only touches bonusTrain, not bonusAtmos
        assertEquals(10.0, stat("che_대의", "bonusAtmos", 10.0))
    }

    // --- onCalcDomestic 징병/모병 cost multipliers ---

    @Test
    fun `recruit cost multipliers on 징병 and 모병`() {
        for (turn in listOf("징병", "모병")) {
            // +20%
            assertEquals(100.0 * 1.2, dom("che_의협", turn, "cost", 100.0))
            assertEquals(100.0 * 1.2, dom("che_패권", turn, "cost", 100.0))
            assertEquals(100.0 * 1.2, dom("che_출세", turn, "cost", 100.0))
            // -20%
            assertEquals(100.0 * 0.8, dom("che_재간", turn, "cost", 100.0))
            assertEquals(100.0 * 0.8, dom("che_유지", turn, "cost", 100.0))
            assertEquals(100.0 * 0.8, dom("che_안전", turn, "cost", 100.0))
        }
    }

    @Test
    fun `recruit cost only fires on cost varType and only 징병 모병 turnType`() {
        // non-cost varType is identity even on 징병
        assertEquals(100.0, dom("che_의협", "징병", "score", 100.0))
        assertEquals(100.0, dom("che_재간", "모병", "success", 100.0))
        // a different turnType is identity even for cost
        assertEquals(100.0, dom("che_의협", "농업", "cost", 100.0))
        assertEquals(100.0, dom("che_안전", "치안", "cost", 100.0))
    }

    @Test
    fun `personalities without a recruit-cost effect leave 징병 cost identity`() {
        for (code in listOf("che_왕좌", "che_대의", "che_정복", "che_할거", "che_은둔")) {
            assertEquals(100.0, dom(code, "징병", "cost", 100.0), "recruit cost must be identity for $code")
        }
    }

    // --- 은둔 단련 success ADDITIVE (the only additive-domestic exception) ---

    @Test
    fun `은둔 단련 success is additive plus 0_1`() {
        assertEquals(0.5 + 0.1, dom("che_은둔", "단련", "success", 0.5))
        // not score, not fail
        assertEquals(0.5, dom("che_은둔", "단련", "score", 0.5))
        // not on a different turnType
        assertEquals(0.5, dom("che_은둔", "징병", "success", 0.5))
        // no other personality touches 단련 success
        for (code in listOf("che_왕좌", "che_의협", "che_재간")) {
            assertEquals(0.5, dom(code, "단련", "success", 0.5), "단련 success must be identity for $code")
        }
    }

    // --- name / info PHP getInfo() format (literal $info string, NOT '장점:X / 단점:Y') ---

    @Test
    fun `name and info match PHP literals`() {
        assertEquals("왕좌", mod("che_왕좌").let { (it as PersonalityModule).name })
        assertEquals("명성 +10%, 사기 -5", (mod("che_왕좌") as PersonalityModule).info)
        assertEquals("대의", (mod("che_대의") as PersonalityModule).name)
        assertEquals("명성 +10%, 훈련 -5", (mod("che_대의") as PersonalityModule).info)
        assertEquals("의협", (mod("che_의협") as PersonalityModule).name)
        assertEquals("사기 +5, 징·모병 비용 +20%", (mod("che_의협") as PersonalityModule).info)
        assertEquals("패권", (mod("che_패권") as PersonalityModule).name)
        assertEquals("훈련 +5, 징·모병 비용 +20%", (mod("che_패권") as PersonalityModule).info)
        assertEquals("정복", (mod("che_정복") as PersonalityModule).name)
        assertEquals("명성 -10%, 사기 +5", (mod("che_정복") as PersonalityModule).info)
        assertEquals("할거", (mod("che_할거") as PersonalityModule).name)
        assertEquals("명성 -10%, 훈련 +5", (mod("che_할거") as PersonalityModule).info)
        assertEquals("출세", (mod("che_출세") as PersonalityModule).name)
        assertEquals("명성 +10%, 징·모병 비용 +20%", (mod("che_출세") as PersonalityModule).info)
        assertEquals("재간", (mod("che_재간") as PersonalityModule).name)
        assertEquals("명성 -10%, 징·모병 비용 -20%", (mod("che_재간") as PersonalityModule).info)
        assertEquals("유지", (mod("che_유지") as PersonalityModule).name)
        assertEquals("훈련 -5, 징·모병 비용 -20%", (mod("che_유지") as PersonalityModule).info)
        assertEquals("안전", (mod("che_안전") as PersonalityModule).name)
        assertEquals("사기 -5, 징·모병 비용 -20%", (mod("che_안전") as PersonalityModule).info)
        assertEquals("은둔", (mod("che_은둔") as PersonalityModule).name)
        assertEquals("명성 -10%, 계급 -10%, 사기 -5, 훈련 -5, 단련 성공률 +10%", (mod("che_은둔") as PersonalityModule).info)
    }

    @Test
    fun `ids match PHP protected id`() {
        assertEquals(0, (mod("che_왕좌") as PersonalityModule).id)
        assertEquals(1, (mod("che_대의") as PersonalityModule).id)
        assertEquals(2, (mod("che_의협") as PersonalityModule).id)
        assertEquals(3, (mod("che_패권") as PersonalityModule).id)
        assertEquals(4, (mod("che_정복") as PersonalityModule).id)
        assertEquals(5, (mod("che_할거") as PersonalityModule).id)
        assertEquals(6, (mod("che_출세") as PersonalityModule).id)
        assertEquals(7, (mod("che_재간") as PersonalityModule).id)
        assertEquals(8, (mod("che_유지") as PersonalityModule).id)
        assertEquals(9, (mod("che_안전") as PersonalityModule).id)
        assertEquals(10, (mod("che_은둔") as PersonalityModule).id)
    }

    // --- None no-op (registry skips it; the singleton folds identity everywhere) ---

    @Test
    fun `None personality is inert identity on every hook`() {
        val none = PersonalityNone
        assertEquals("-", none.name)
        assertEquals("", none.info)
        assertEquals(-1, none.id)
        assertEquals(100.0, none.onCalcStat(general(), "experience", 100.0))
        assertEquals(100.0, none.onCalcStat(general(), "dedication", 100.0))
        assertEquals(10.0, none.onCalcStat(general(), "bonusTrain", 10.0))
        assertEquals(10.0, none.onCalcStat(general(), "bonusAtmos", 10.0))
        assertEquals(100.0, none.onCalcDomestic(general(), "징병", "cost", 100.0))
        assertEquals(0.5, none.onCalcDomestic(general(), "단련", "success", 0.5))
        // and the registry never hands it out
        assertNull(registry.resolve("None"))
    }
}
