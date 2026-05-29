package opensamguk.logic.stats

import opensamguk.logic.domain.General

/** One of the 9 action-stack sources (nation-type / officer / domestic-special / war-special /
 *  personality / crew / inheritance / scenario / item). P1 wires ZERO modules — identity fold. */
interface GeneralActionModule {
    /** onCalcStat(statName, value) -> value. Default identity. */
    fun onCalcStat(general: General, statName: String, value: Double): Double = value
    /** onCalcDomestic(actionKey, varType['cost'|'score'|'success'|'fail'], value) -> value. Default identity. */
    fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double): Double = value
}

class GeneralActionPipeline(private val modules: List<GeneralActionModule> = emptyList()) {
    fun onCalcStat(general: General, statName: String, value: Double): Double =
        modules.fold(value) { acc, m -> m.onCalcStat(general, statName, acc) }
    fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double): Double =
        modules.fold(value) { acc, m -> m.onCalcDomestic(general, actionKey, varType, acc) }
}
