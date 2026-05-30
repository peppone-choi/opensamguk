package opensamguk.logic.items

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Port-faithful tests for the domestic-hook + override-stat items in P2-domestic scope (IT2).
 *
 * PHP grand truth (ActionItem PHP classes):
 *   - che_내정_납금박산로: onCalcDomestic — 8 turnTypes [상업,농업,기술,성벽,수비,치안,민심,인구], success +0.15.
 *   - che_조달_주판: onCalcDomestic — 조달, success +0.2, score *2.
 *   - che_계략_삼략: onCalcDomestic — 계략, success +0.2 (warMagic* onCalcStat are war-side, stubbed/identity here).
 *   - che_능력치_*_*: onCalcStat — strength/intel/leadership += 5 + valueFit(intdiv(relYear,4), 0, 12). NOT a BaseStatItem.
 *   - che_숙련_동작: onCalcStat — addDex *1.20 (MULTIPLICATIVE).
 *   - che_전략_평만지장도: onCalcStrategic — delay → Util::round(value*0.80) (phpRound).
 *   - parent-first decorators (변도론/백상/기주마): extend BaseStatItem → onCalcStat calls parent (+stat) FIRST,
 *     then applies the war-side delta (warMagicTrialProb/killRice/initWarPhase — stubbed identity in domestic scope).
 */
class ItemHooksTest {

    private fun general() = General(
        id = 1, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 1000, rice = 1000,
    )

    private fun registry(relYear: Int = 0) = ItemRegistry { relYear }

    // === 납금박산로 — 8-turnType success +0.15 ===

    @Test
    fun `napgeum success buff fires on all 8 domestic turnTypes`() {
        val m = registry().resolve("che_내정_납금박산로")!!
        val g = general()
        for (t in listOf("상업", "농업", "기술", "성벽", "수비", "치안", "민심", "인구")) {
            assertEquals(0.5 + 0.15, m.onCalcDomestic(g, t, "success", 0.5), 1e-9, "turnType=$t")
        }
    }

    @Test
    fun `napgeum identity outside its turnTypes and varTypes`() {
        val m = registry().resolve("che_내정_납금박산로")!!
        val g = general()
        assertEquals(0.5, m.onCalcDomestic(g, "조달", "success", 0.5))      // not in list
        assertEquals(0.5, m.onCalcDomestic(g, "상업", "score", 0.5))        // not success
        assertEquals(0.5, m.onCalcDomestic(g, "상업", "cost", 0.5))
    }

    // === 조달주판 — 조달 success +0.2 AND score *2 ===

    @Test
    fun `jodal jupan adds success and doubles score`() {
        val m = registry().resolve("che_조달_주판")!!
        val g = general()
        assertEquals(0.4 + 0.2, m.onCalcDomestic(g, "조달", "success", 0.4), 1e-9)
        assertEquals(0.5 * 2, m.onCalcDomestic(g, "조달", "score", 0.5))
        assertEquals(0.5, m.onCalcDomestic(g, "농업", "score", 0.5)) // wrong turnType
        assertEquals(0.5, m.onCalcDomestic(g, "조달", "cost", 0.5))   // wrong varType
    }

    // === 계략 삼략 — 계략 success +0.2 (domestic), warMagic* are war-side ===

    @Test
    fun `samryak adds calculation success in domestic scope`() {
        val m = registry().resolve("che_계략_삼략")!!
        val g = general()
        assertEquals(0.3 + 0.2, m.onCalcDomestic(g, "계략", "success", 0.3), 1e-9)
        assertEquals(0.3, m.onCalcDomestic(g, "계략", "score", 0.3))
        assertEquals(0.3, m.onCalcDomestic(g, "상업", "success", 0.3))
    }

    // === 능력치 ability items — year-scaled override (NOT BaseStatItem +stat) ===

    @Test
    fun `dugangju adds 5 to strength at relYear 0`() {
        val m = registry(relYear = 0).resolve("che_능력치_무력_두강주")!!
        assertEquals(60.0 + 5.0 + 0.0, m.onCalcStat(general(), "strength", 60.0))
        assertEquals(80.0, m.onCalcStat(general(), "intel", 80.0)) // only strength
    }

    @Test
    fun `dugangju year-scaled by intdiv relYear over 4 clamped to 12`() {
        // relYear 4 → intdiv 1 → +5+1 ; relYear 7 → intdiv 1 ; relYear 8 → +5+2 ; relYear 100 → clamp 12 → +5+12
        assertEquals(60.0 + 5.0 + 1.0, registry(4).resolve("che_능력치_무력_두강주")!!.onCalcStat(general(), "strength", 60.0))
        assertEquals(60.0 + 5.0 + 1.0, registry(7).resolve("che_능력치_무력_두강주")!!.onCalcStat(general(), "strength", 60.0))
        assertEquals(60.0 + 5.0 + 2.0, registry(8).resolve("che_능력치_무력_두강주")!!.onCalcStat(general(), "strength", 60.0))
        assertEquals(60.0 + 5.0 + 12.0, registry(100).resolve("che_능력치_무력_두강주")!!.onCalcStat(general(), "strength", 60.0))
    }

    @Test
    fun `igangju scales intel and boryeong scales leadership`() {
        assertEquals(80.0 + 5.0 + 2.0, registry(8).resolve("che_능력치_지력_이강주")!!.onCalcStat(general(), "intel", 80.0))
        assertEquals(70.0 + 5.0 + 2.0, registry(8).resolve("che_능력치_통솔_보령압주")!!.onCalcStat(general(), "leadership", 70.0))
        // wrong stat untouched
        assertEquals(60.0, registry(8).resolve("che_능력치_지력_이강주")!!.onCalcStat(general(), "strength", 60.0))
    }

    // === 동작 — addDex *1.20 multiplicative ===

    @Test
    fun `dongjak multiplies addDex by 1_20`() {
        val m = registry().resolve("che_숙련_동작")!!
        assertEquals(0.5 * 1.20, m.onCalcStat(general(), "addDex", 0.5), 1e-9)
        assertEquals(0.5, m.onCalcStat(general(), "strength", 0.5)) // only addDex
    }

    // === 평만지장도 — strategic delay phpRound(value*0.80) ===

    @Test
    fun `pyeongman scales strategic delay by 0_80 with phpRound`() {
        val m = registry().resolve("che_전략_평만지장도")!!
        val g = general()
        // 10 * 0.80 = 8 ; 9*0.8=7.2→round 7 ; 7*0.8=5.6→round 6 ; 5*0.8=4.0→4
        assertEquals(8.0, m.onCalcStrategic(g, "delay", 10.0))
        assertEquals(7.0, m.onCalcStrategic(g, "delay", 9.0))
        assertEquals(6.0, m.onCalcStrategic(g, "delay", 7.0))
        assertEquals(4.0, m.onCalcStrategic(g, "delay", 5.0))
        // globalDelay untouched
        assertEquals(9.0, m.onCalcStrategic(g, "globalDelay", 9.0))
    }

    // === parent-first decorators — base +stat applies in domestic scope ===

    @Test
    fun `byeondoron applies parent stat plus first then identity war delta`() {
        // 변도론 = che_서적_03_변도론 → BaseStatItem 지력 +3, plus a war-only warMagicTrialProb +0.02 (stubbed).
        val m = registry().resolve("che_서적_03_변도론")!!
        // parent +stat is active in domestic scope:
        assertEquals(80.0 + 3.0, m.onCalcStat(general(), "intel", 80.0))
        // the war-side delta does NOT touch a domestic stat:
        assertEquals(60.0, m.onCalcStat(general(), "strength", 60.0))
    }

    @Test
    fun `baeksang and gijuma apply their parent stat in domestic scope`() {
        // 백상 = che_명마_07_백상 → 통솔 +7 ; 기주마 = che_명마_07_기주마 → 통솔 +7. War deltas stubbed.
        assertEquals(70.0 + 7.0, registry().resolve("che_명마_07_백상")!!.onCalcStat(general(), "leadership", 70.0))
        assertEquals(70.0 + 7.0, registry().resolve("che_명마_07_기주마")!!.onCalcStat(general(), "leadership", 70.0))
    }

    @Test
    fun `extra-hook items cache to one instance per code`() {
        val r = registry()
        assertSame(r.resolve("che_조달_주판"), r.resolve("che_조달_주판"))
        assertSame(r.resolve("che_능력치_무력_두강주"), r.resolve("che_능력치_무력_두강주"))
    }
}
