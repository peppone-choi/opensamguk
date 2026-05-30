package opensamguk.logic.traits

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Port-faithful tests for the 15 nation-type modules (source #1 of the 9-source action stack).
 *
 * PHP grand truth = `ActionNationType/` (each `che_X.php` `extends \sammo\BaseNation`):
 *   - onCalcDomestic($turnType, $varType, $value, $aux): per-turnType score×/cost×/success+ buffs.
 *   - onCalcNationalIncome($type, $amount): gold/rice/pop multipliers; pop branch gated `&& amount>0`.
 *   - onCalcStrategic($turnType, $varType, $value): 음양가 delay = Util::round(v*4/3);
 *     종횡가 delay = Util::round(v*3/4), globalDelay = Util::round(v/2). Util::round returns int.
 *   - getInfo() = "{$pros} {$cons}" (single space). None / che_중립 are inert (no overrides) → identity.
 */
class NationTypeTest {

    private val g = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
    )

    private fun dom(m: GeneralActionModule, turnType: String, varType: String, v: Double): Double =
        m.onCalcDomestic(g, turnType, varType, v)

    private fun inc(m: GeneralActionModule, type: String, v: Double): Double =
        m.onCalcNationalIncome(g, type, v)

    // ---- registry resolution ----

    @Test
    fun `registry resolves all 13 che types and skips None and che_중립`() {
        for (code in listOf(
            "che_덕가", "che_도가", "che_도적", "che_명가", "che_묵가", "che_법가",
            "che_병가", "che_불가", "che_오두미도", "che_유가", "che_음양가",
            "che_종횡가", "che_태평도",
        )) {
            val m = NationTypeRegistry.resolve(code)
            requireNotNull(m) { "expected a module for $code" }
        }
        // inert types fold as identity in PHP (no onCalc override) → resolved to null (skipped slot).
        assertNull(NationTypeRegistry.resolve("che_중립"))
        assertNull(NationTypeRegistry.resolve("None"))
        assertNull(NationTypeRegistry.resolve(null))
        assertNull(NationTypeRegistry.resolve(""))
    }

    // ---- 덕가 (che_덕가.php) ----

    @Test
    fun `덕가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_덕가")!!
        // 치안: score×1.1 cost×0.8
        assertEquals(110.00000000000001, dom(m, "치안", "score", 100.0))
        assertEquals(80.0, dom(m, "치안", "cost", 100.0))
        // 민심/인구: score×1.1 cost×0.8
        assertEquals(110.00000000000001, dom(m, "민심", "score", 100.0))
        assertEquals(80.0, dom(m, "인구", "cost", 100.0))
        // 수비/성벽: score×0.9 cost×1.2
        assertEquals(90.0, dom(m, "수비", "score", 100.0))
        assertEquals(120.0, dom(m, "성벽", "cost", 100.0))
        // unrelated turnType identity
        assertEquals(100.0, dom(m, "농업", "score", 100.0))
        // income: rice×0.9, pop×1.2 (>0), gold identity
        assertEquals(90.0, inc(m, "rice", 100.0))
        assertEquals(120.0, inc(m, "pop", 100.0))
        assertEquals(100.0, inc(m, "gold", 100.0))
        // pop guard: amount<=0 untouched
        assertEquals(-50.0, inc(m, "pop", -50.0))
        assertEquals(0.0, inc(m, "pop", 0.0))
    }

    // ---- 도가 (che_도가.php) ----

    @Test
    fun `도가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_도가")!!
        assertEquals(90.0, dom(m, "기술", "score", 100.0))
        assertEquals(120.0, dom(m, "기술", "cost", 100.0))
        assertEquals(90.0, dom(m, "치안", "score", 100.0))
        assertEquals(120.0, dom(m, "치안", "cost", 100.0))
        assertEquals(120.0, inc(m, "pop", 100.0))
        assertEquals(100.0, inc(m, "gold", 100.0))
    }

    // ---- 도적 (che_도적.php) — ADDITIVE 계략 success+0.1 ----

    @Test
    fun `도적 domestic additive scheme and income`() {
        val m = NationTypeRegistry.resolve("che_도적")!!
        assertEquals(90.0, dom(m, "치안", "score", 100.0))
        assertEquals(120.0, dom(m, "치안", "cost", 100.0))
        assertEquals(90.0, dom(m, "민심", "score", 100.0))
        assertEquals(120.0, dom(m, "인구", "cost", 100.0))
        // ADDITIVE: 계략 success +0.1
        assertEquals(0.7, dom(m, "계략", "success", 0.6))
        // 계략 score/cost untouched
        assertEquals(100.0, dom(m, "계략", "score", 100.0))
        // income gold×0.9
        assertEquals(90.0, inc(m, "gold", 100.0))
    }

    // ---- 명가 (che_명가.php) ----

    @Test
    fun `명가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_명가")!!
        assertEquals(110.00000000000001, dom(m, "기술", "score", 100.0))
        assertEquals(80.0, dom(m, "기술", "cost", 100.0))
        assertEquals(90.0, dom(m, "수비", "score", 100.0))
        assertEquals(120.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(90.0, inc(m, "rice", 100.0))
        assertEquals(120.0, inc(m, "pop", 100.0))
    }

    // ---- 묵가 (che_묵가.php) — domestic only, NO income override ----

    @Test
    fun `묵가 domestic and identity income`() {
        val m = NationTypeRegistry.resolve("che_묵가")!!
        assertEquals(110.00000000000001, dom(m, "수비", "score", 100.0))
        assertEquals(80.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(90.0, dom(m, "기술", "score", 100.0))
        assertEquals(120.0, dom(m, "기술", "cost", 100.0))
        // no onCalcNationalIncome override → identity for all types
        assertEquals(100.0, inc(m, "gold", 100.0))
        assertEquals(100.0, inc(m, "rice", 100.0))
        assertEquals(100.0, inc(m, "pop", 100.0))
    }

    // ---- 법가 (che_법가.php) ----

    @Test
    fun `법가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_법가")!!
        assertEquals(110.00000000000001, dom(m, "치안", "score", 100.0))
        assertEquals(80.0, dom(m, "치안", "cost", 100.0))
        assertEquals(90.0, dom(m, "민심", "score", 100.0))
        assertEquals(120.0, dom(m, "인구", "cost", 100.0))
        // gold×1.1, pop×0.8 (>0)
        assertEquals(110.00000000000001, inc(m, "gold", 100.0))
        assertEquals(80.0, inc(m, "pop", 100.0))
        // pop guard
        assertEquals(-100.0, inc(m, "pop", -100.0))
    }

    // ---- 병가 (che_병가.php) ----

    @Test
    fun `병가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_병가")!!
        assertEquals(110.00000000000001, dom(m, "기술", "score", 100.0))
        assertEquals(80.0, dom(m, "기술", "cost", 100.0))
        assertEquals(110.00000000000001, dom(m, "수비", "score", 100.0))
        assertEquals(80.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(90.0, dom(m, "민심", "score", 100.0))
        assertEquals(120.0, dom(m, "인구", "cost", 100.0))
        assertEquals(80.0, inc(m, "pop", 100.0))
        assertEquals(100.0, inc(m, "gold", 100.0))
    }

    // ---- 불가 (che_불가.php) ----

    @Test
    fun `불가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_불가")!!
        assertEquals(110.00000000000001, dom(m, "민심", "score", 100.0))
        assertEquals(80.0, dom(m, "인구", "cost", 100.0))
        assertEquals(110.00000000000001, dom(m, "수비", "score", 100.0))
        assertEquals(80.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(90.0, inc(m, "gold", 100.0))
        assertEquals(100.0, inc(m, "pop", 100.0))
    }

    // ---- 오두미도 (che_오두미도.php) ----

    @Test
    fun `오두미도 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_오두미도")!!
        assertEquals(90.0, dom(m, "기술", "score", 100.0))
        assertEquals(120.0, dom(m, "기술", "cost", 100.0))
        assertEquals(90.0, dom(m, "수비", "score", 100.0))
        assertEquals(120.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(90.0, dom(m, "농업", "score", 100.0))
        assertEquals(120.0, dom(m, "상업", "cost", 100.0))
        // rice×1.1, pop×1.2 (>0)
        assertEquals(110.00000000000001, inc(m, "rice", 100.0))
        assertEquals(120.0, inc(m, "pop", 100.0))
    }

    // ---- 유가 (che_유가.php) ----

    @Test
    fun `유가 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_유가")!!
        assertEquals(110.00000000000001, dom(m, "농업", "score", 100.0))
        assertEquals(80.0, dom(m, "상업", "cost", 100.0))
        assertEquals(110.00000000000001, dom(m, "민심", "score", 100.0))
        assertEquals(80.0, dom(m, "인구", "cost", 100.0))
        assertEquals(90.0, inc(m, "rice", 100.0))
        assertEquals(100.0, inc(m, "pop", 100.0))
    }

    // ---- 음양가 (che_음양가.php) — onCalcStrategic delay = round(v*4/3) ----

    @Test
    fun `음양가 domestic income and strategic delay round`() {
        val m = NationTypeRegistry.resolve("che_음양가")!!
        assertEquals(110.00000000000001, dom(m, "농업", "score", 100.0))
        assertEquals(80.0, dom(m, "상업", "cost", 100.0))
        assertEquals(90.0, dom(m, "기술", "score", 100.0))
        assertEquals(120.0, dom(m, "기술", "cost", 100.0))
        assertEquals(120.0, inc(m, "pop", 100.0))
        // strategic delay = Util::round(v*4/3) → int-valued double
        // 9*4/3 = 12.0 → round 12
        assertEquals(12.0, m.onCalcStrategic(g, "delay", 9.0))
        // 10*4/3 = 13.333… → round 13
        assertEquals(13.0, m.onCalcStrategic(g, "delay", 10.0))
        // 7*4/3 = 9.333… → round 9
        assertEquals(9.0, m.onCalcStrategic(g, "delay", 7.0))
        // globalDelay untouched (no branch) → identity
        assertEquals(9.0, m.onCalcStrategic(g, "globalDelay", 9.0))
    }

    // ---- 종횡가 (che_종횡가.php) — delay=round(v*3/4), globalDelay=round(v/2) ----

    @Test
    fun `종횡가 domestic income and strategic delays round`() {
        val m = NationTypeRegistry.resolve("che_종횡가")!!
        assertEquals(110.00000000000001, dom(m, "수비", "score", 100.0))
        assertEquals(80.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(90.0, dom(m, "농업", "score", 100.0))
        assertEquals(120.0, dom(m, "상업", "cost", 100.0))
        assertEquals(90.0, inc(m, "gold", 100.0))
        // delay = Util::round(v*3/4); 9*3/4 = 6.75 → round 7 (half-away-from-zero)
        assertEquals(7.0, m.onCalcStrategic(g, "delay", 9.0))
        // 10*3/4 = 7.5 → round 8
        assertEquals(8.0, m.onCalcStrategic(g, "delay", 10.0))
        // globalDelay = Util::round(v/2); 9/2 = 4.5 → round 5
        assertEquals(5.0, m.onCalcStrategic(g, "globalDelay", 9.0))
        // 7/2 = 3.5 → round 4
        assertEquals(4.0, m.onCalcStrategic(g, "globalDelay", 7.0))
    }

    // ---- 태평도 (che_태평도.php) ----

    @Test
    fun `태평도 domestic and income`() {
        val m = NationTypeRegistry.resolve("che_태평도")!!
        assertEquals(110.00000000000001, dom(m, "민심", "score", 100.0))
        assertEquals(80.0, dom(m, "인구", "cost", 100.0))
        assertEquals(90.0, dom(m, "기술", "score", 100.0))
        assertEquals(120.0, dom(m, "기술", "cost", 100.0))
        assertEquals(90.0, dom(m, "수비", "score", 100.0))
        assertEquals(120.0, dom(m, "성벽", "cost", 100.0))
        assertEquals(120.0, inc(m, "pop", 100.0))
    }

    // ---- name / info PHP format ----

    @Test
    fun `name and info match PHP getInfo format`() {
        // getInfo() = "{pros} {cons}" — single space.
        assertEquals("덕가", CheDeokga.typeName)
        assertEquals("치안↑ 인구↑ 민심↑ 쌀수입↓ 수성↓", CheDeokga.info)
        assertEquals("종횡가", CheJonghoengga.typeName)
        assertEquals("전략↑ 수성↑ 금수입↓ 농상↓", CheJonghoengga.info)
        assertEquals("도가", CheDoga.typeName)
        assertEquals("인구↑ 기술↓ 치안↓", CheDoga.info)
    }

    // ---- che_중립 / None inert ----

    @Test
    fun `che_중립 and None fold as identity`() {
        // Resolved to null (skipped) — but if the singleton is folded directly it is the identity.
        assertSame(null, NationTypeRegistry.resolve("che_중립"))
        // No magnitude on any hook for the neutral object.
        assertEquals(100.0, CheNeutral.onCalcDomestic(g, "치안", "score", 100.0))
        assertEquals(100.0, CheNeutral.onCalcNationalIncome(g, "pop", 100.0))
        assertEquals(9.0, CheNeutral.onCalcStrategic(g, "delay", 9.0))
        assertEquals("-", CheNeutral.typeName)
        assertEquals(" ", CheNeutral.info)
    }
}
