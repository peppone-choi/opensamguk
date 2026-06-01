package opensamguk.logic.inheritance

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.war.WarStatName

/**
 * War action module for inherit buffs.
 * Applies buff.level * 0.01 modifiers to war stats (self buff = addition, opponent buff = subtraction).
 *
 * Implements BOTH [WarActionModule] (for dedicated war-module registries) AND
 * [GeneralActionModule] (for integration into the existing [GeneralActionPipeline]).
 *
 * Faithful port of the TypeScript war module (legacy_core2026 inheritBuff.ts).
 *
 * @param buff The resolved [InheritBuff] (from triggerState.meta.inheritBuff or meta.inheritBuff)
 */
class InheritBuffWarModule(
    private val buff: InheritBuff,
) : WarActionModule, GeneralActionModule {

    // ── WarActionModule hooks ────────────────────────────────────────────

    override fun onCalcStat(statName: String, value: Double): Double {
        return when (statName) {
            WarStatName.WAR_AVOID_RATIO -> value + buff.warAvoidRatio * 0.01
            WarStatName.WAR_CRITICAL_RATIO -> value + buff.warCriticalRatio * 0.01
            WarStatName.WAR_MAGIC_TRIAL_PROB -> value + buff.warMagicTrialProb * 0.01
            else -> value
        }
    }

    override fun onCalcOpposeStat(statName: String, value: Double): Double {
        return when (statName) {
            WarStatName.WAR_AVOID_RATIO -> value - buff.warAvoidRatioOppose * 0.01
            WarStatName.WAR_CRITICAL_RATIO -> value - buff.warCriticalRatioOppose * 0.01
            WarStatName.WAR_MAGIC_TRIAL_PROB -> value - buff.warMagicTrialProbOppose * 0.01
            else -> value
        }
    }

    // ── GeneralActionModule hooks (pipeline integration) ─────────────────

    /** Routes through [onCalcStat] — the pipeline calls this for all stat calculations. */
    override fun onCalcStat(
        general: General,
        statName: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double = onCalcStat(statName, value)

    /** Routes through [onCalcOpposeStat] — the pipeline calls this for opponent stat calculations. */
    override fun onCalcOpposeStat(
        general: General,
        statName: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double = onCalcOpposeStat(statName, value)
}
