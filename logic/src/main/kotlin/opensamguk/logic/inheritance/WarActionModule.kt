package opensamguk.logic.inheritance

import opensamguk.logic.domain.General

/**
 * War-specific action module interface. Mirrors [opensamguk.logic.stats.GeneralActionModule]
 * but provides a dedicated type for war-oriented buff modules.
 *
 * In the existing pipeline, war stat folding goes through
 * [opensamguk.logic.stats.GeneralActionPipeline.onCalcStat] /
 * [onCalcOpposeStat] which accept [GeneralActionModule] instances. A [WarActionModule]
 * can be adapted to [GeneralActionModule] by wrapping its hooks.
 *
 * This interface is DEFINED here (P6) for type clarity; the concrete war buff module
 * ([InheritBuffWarModule]) implements BOTH this interface AND [GeneralActionModule] for
 * direct pipeline integration.
 */
interface WarActionModule {
    /**
     * Apply a stat modifier for the owner (self) side of a battle calculation.
     * Returns the modified value.
     */
    fun onCalcStat(statName: String, value: Double): Double = value

    /**
     * Apply a stat modifier for the opponent side of a battle calculation.
     * Returns the modified value (typically a debuff = subtraction).
     */
    fun onCalcOpposeStat(statName: String, value: Double): Double = value
}
