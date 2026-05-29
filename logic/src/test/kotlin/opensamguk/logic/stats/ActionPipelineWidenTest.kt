package opensamguk.logic.stats

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-faithful tests for the widened action pipeline.
 *
 * PHP grand truth (General.php):
 *   - onCalcStrategic(turnType, varType['delay'|'globalDelay'], value)  : 853-863 (folds full action list)
 *   - onCalcOpposeStat(general, statName, value, aux)                   : 840-851 (folds full action list)
 *   - onCalcStat(general, statName, value, aux)                         : 827-838 (aux threaded — ['armType'=>…])
 *   - onCalcDomestic(turnType, varType, value, aux)                     : 815-825 (aux threaded)
 *   - onCalcNationalIncome(type, amount)                               : 865-875 — BUT the only call site
 *     (Event/Action/ProcessSemiAnnual.php:46) invokes it on the NATION-TYPE object directly
 *     ($nationTypeObj->onCalcNationalIncome('pop', $popRatio)), NOT on $general → income is folded
 *     over the nation-type source ONLY (research Unit 12 asymmetry).
 */
class ActionPipelineWidenTest {

    private val general = General(
        id = 1, nationId = 1, cityId = 1,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
    )

    // ---- empty pipeline = identity on all 5 hooks ----

    @Test
    fun `empty pipeline identity on all five hooks`() {
        val p = GeneralActionPipeline()
        assertEquals(80.0, p.onCalcStat(general, "intelligence", 80.0))
        assertEquals(16.0, p.onCalcDomestic(general, "상업", "cost", 16.0))
        assertEquals(9.0, p.onCalcStrategic(general, "che_의병모집", "delay", 9.0))
        assertEquals(72.0, p.onCalcOpposeStat(general, "strength", 72.0))
        // income fold over a null nation-type source = identity
        assertEquals(0.075, p.nationIncomeFold(null, "pop", 0.075))
    }

    // ---- a one-module stub folds each hook ----

    @Test
    fun `one module folds strategic delay`() {
        val mod = object : GeneralActionModule {
            override fun onCalcStrategic(general: General, varType: String, value: Double): Double =
                if (varType == "delay") value - 1.0 else value
        }
        val p = GeneralActionPipeline(listOf(mod))
        assertEquals(8.0, p.onCalcStrategic(general, "che_의병모집", "delay", 9.0))
        // globalDelay untouched by the stub
        assertEquals(9.0, p.onCalcStrategic(general, "che_의병모집", "globalDelay", 9.0))
    }

    @Test
    fun `one module folds opposeStat`() {
        val mod = object : GeneralActionModule {
            override fun onCalcOpposeStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
                value * 0.9
        }
        val p = GeneralActionPipeline(listOf(mod))
        assertEquals(72.0 * 0.9, p.onCalcOpposeStat(general, "strength", 72.0))
    }

    // ---- aux is threaded through onCalcStat / onCalcDomestic ----

    @Test
    fun `aux armType is threaded into onCalcStat`() {
        val mod = object : GeneralActionModule {
            override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
                if (aux["armType"] == 1) value + 10.0 else value
        }
        val p = GeneralActionPipeline(listOf(mod))
        assertEquals(90.0, p.onCalcStat(general, "addDex", 80.0, mapOf("armType" to 1)))
        // no aux (default empty) = no bonus
        assertEquals(80.0, p.onCalcStat(general, "addDex", 80.0))
    }

    @Test
    fun `aux armType is threaded into onCalcDomestic`() {
        val mod = object : GeneralActionModule {
            override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
                if (varType == "cost" && aux["armType"] == 7) value * 1.2 else value
        }
        val p = GeneralActionPipeline(listOf(mod))
        assertEquals(120.0, p.onCalcDomestic(general, "징병", "cost", 100.0, mapOf("armType" to 7)))
        assertEquals(100.0, p.onCalcDomestic(general, "징병", "cost", 100.0))
    }

    // ---- income asymmetry: folded over the nation-type source ONLY, not the general stack ----

    @Test
    fun `income fold uses only the nation-type source not the general pipeline`() {
        // A nation-type module that boosts pop income by 1.2x when positive (che_도가 behaviour).
        val nationType = object : GeneralActionModule {
            override fun onCalcNationalIncome(general: General, type: String, value: Double): Double =
                if (type == "pop" && value > 0) value * 1.2 else value
        }
        // A *general-stack* module that would ALSO touch income — must NOT participate in the income fold.
        val otherStackModule = object : GeneralActionModule {
            override fun onCalcNationalIncome(general: General, type: String, value: Double): Double = value * 999.0
        }
        // The general pipeline carries the noisy module; income must ignore it.
        val p = GeneralActionPipeline(listOf(otherStackModule))
        // nationIncomeFold folds the single nation-type source against `general`.
        assertEquals(0.075 * 1.2, p.nationIncomeFold(nationType, "pop", 0.075, general))
        // negative pop ratio is untouched by che_도가 (only >0 boosts)
        assertEquals(-0.1, p.nationIncomeFold(nationType, "pop", -0.1, general))
    }

    // ---- MODULE_ORDER documents the getActionList fold order ----

    @Test
    fun `MODULE_ORDER documents getActionList fold order`() {
        assertEquals(
            listOf(
                "nationType", "officer", "specialDomestic", "specialWar",
                "personality", "crew", "inherit", "scenario",
                "horse", "weapon", "book", "item",
            ),
            GeneralActionPipeline.MODULE_ORDER,
        )
    }
}
