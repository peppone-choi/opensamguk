package com.opensam.engine.trigger

import com.opensam.entity.General

/**
 * General command triggers (legacy parity: GeneralTrigger/).
 *
 * These modify command behavior via onCalcDomestic hooks:
 *   - cost, rice, train, atmos, success, fail, score adjustments
 *
 * Fired by TurnExecutionHelper::preprocessCommand() before command runs.
 */
interface GeneralTrigger : ObjectTrigger {
    /**
     * Modify domestic command parameters.
     *
     * @param turnType command key (징병, 조달, 주민선정, etc.)
     * @param varType  parameter to modify (cost, rice, train, atmos, success, fail, score)
     * @param value    current value
     * @param aux      extra context (e.g., armType)
     * @return modified value
     */
    fun onCalcDomestic(
        general: General,
        turnType: String,
        varType: String,
        value: Double,
        aux: Map<String, Any> = emptyMap(),
    ): Double = value

    /**
     * Modify stat calculations.
     *
     * @param statName stat key (leadership, strength, intel, addDex, experience, etc.)
     * @param value    current value
     * @param aux      extra context
     * @return modified value
     */
    fun onCalcStat(
        general: General,
        statName: String,
        value: Double,
        aux: Map<String, Any> = emptyMap(),
    ): Double = value
}

// ========== Built-in General Triggers ==========

/**
 * 부상경감: Reduces injury by 1 each turn (priority BEGIN).
 * Legacy: GeneralTrigger/che_부상경감.php
 */
class InjuryReductionTrigger(private val general: General) : GeneralTrigger {
    override val uniqueId = "부상경감_${general.id}"
    override val priority = TriggerPriority.BEGIN

    override fun action(env: TriggerEnv): Boolean {
        if (general.injury > 0) {
            general.injury = (general.injury - 1).toShort()
            env.vars["injuryReduced"] = true
        }
        return true
    }
}

/**
 * 병력군량소모: Consume rice for troops each turn (priority FINAL).
 * Legacy: GeneralTrigger/che_병력군량소모.php
 *
 * Rice consumption = crew / 100 (minimum 1 if crew > 0).
 * If not enough rice, crew loses atmos.
 */
class TroopConsumptionTrigger(private val general: General) : GeneralTrigger {
    override val uniqueId = "병력군량소모_${general.id}"
    override val priority = TriggerPriority.FINAL

    override fun action(env: TriggerEnv): Boolean {
        if (general.crew <= 0) return true

        val riceNeeded = maxOf(general.crew / 100, 1)
        if (general.rice >= riceNeeded) {
            general.rice -= riceNeeded
        } else {
            // Not enough rice - morale drops
            general.rice = 0
            val atmosDrop = minOf(5, general.atmos.toInt())
            general.atmos = (general.atmos - atmosDrop).toShort()
            env.vars["troopStarving"] = true
        }
        return true
    }
}

/**
 * Build the pre-turn trigger list for a general.
 * Legacy: TurnExecutionHelper::preprocessCommand()
 */
fun buildPreTurnTriggers(general: General): List<GeneralTrigger> {
    val triggers = mutableListOf<GeneralTrigger>()

    // Always-present triggers
    triggers.add(InjuryReductionTrigger(general))
    triggers.add(TroopConsumptionTrigger(general))

    // TODO: Add special/personality/item-based triggers from general's action list

    return triggers
}

/**
 * Apply onCalcDomestic across a list of triggers.
 */
fun applyDomesticModifiers(
    triggers: List<GeneralTrigger>,
    general: General,
    turnType: String,
    varType: String,
    baseValue: Double,
    aux: Map<String, Any> = emptyMap(),
): Double {
    var value = baseValue
    for (trigger in triggers.sortedBy { it.priority }) {
        value = trigger.onCalcDomestic(general, turnType, varType, value, aux)
    }
    return value
}

/**
 * Apply onCalcStat across a list of triggers.
 */
fun applyStatModifiers(
    triggers: List<GeneralTrigger>,
    general: General,
    statName: String,
    baseValue: Double,
    aux: Map<String, Any> = emptyMap(),
): Double {
    var value = baseValue
    for (trigger in triggers.sortedBy { it.priority }) {
        value = trigger.onCalcStat(general, statName, value, aux)
    }
    return value
}
