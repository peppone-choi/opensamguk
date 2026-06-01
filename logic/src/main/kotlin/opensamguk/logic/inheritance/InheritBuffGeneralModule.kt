package opensamguk.logic.inheritance

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.war.WarStatName

/**
 * General (내정) action module for inherit buffs.
 * Applies buff.level * 0.01 modifiers to domestic success/fail rates.
 *
 * Faithful port of the TypeScript general module (legacy_core2026 inheritBuff.ts).
 * Integrates into the existing [GeneralActionPipeline] via [GeneralActionModule.onCalcDomestic].
 *
 * @param buff The resolved [InheritBuff] (from triggerState.meta.inheritBuff or meta.inheritBuff)
 */
class InheritBuffGeneralModule(
    private val buff: InheritBuff,
) : GeneralActionModule {

    /**
     * onCalcDomestic hook — applies success/fail buffs.
     *
     * [varType] is one of: "cost", "score", "success", "fail" (DomesticVarType strings).
     * Only "success" and "fail" are modified by inherit buffs.
     */
    override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double {
        return when (varType) {
            DOMESTIC_SUCCESS -> value + buff.success * 0.01
            DOMESTIC_FAIL -> value - buff.fail * 0.01
            else -> value
        }
    }

    companion object {
        /** The varType string for 내정 성공 확률 */
        const val DOMESTIC_SUCCESS = "success"
        /** The varType string for 내정 실패 확률 */
        const val DOMESTIC_FAIL = "fail"
    }
}
