package com.opensam.engine.trigger

import com.opensam.engine.modifier.ActionModifier
import com.opensam.engine.modifier.DomesticContext
import com.opensam.engine.modifier.StatContext
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

class ModifierBridgeTrigger(
    private val general: General,
    private val modifiers: List<ActionModifier>,
) : GeneralTrigger {
    override val uniqueId = "modifier_bridge_${general.id}"
    override val priority = TriggerPriority.POST

    override fun action(env: TriggerEnv): Boolean = true

    override fun onCalcDomestic(
        general: General,
        turnType: String,
        varType: String,
        value: Double,
        aux: Map<String, Any>,
    ): Double {
        val baseCtx = when (varType) {
            "cost" -> DomesticContext(costMultiplier = value, actionCode = turnType)
            "success" -> DomesticContext(successMultiplier = value, actionCode = turnType)
            "fail" -> DomesticContext(failMultiplier = value, actionCode = turnType)
            "score" -> DomesticContext(scoreMultiplier = value, actionCode = turnType)
            "train" -> DomesticContext(trainMultiplier = value, actionCode = turnType)
            "atmos" -> DomesticContext(atmosMultiplier = value, actionCode = turnType)
            else -> return value
        }

        val modified = modifiers.fold(baseCtx) { ctx, modifier -> modifier.onCalcDomestic(ctx) }
        return when (varType) {
            "cost" -> modified.costMultiplier
            "success" -> modified.successMultiplier
            "fail" -> modified.failMultiplier
            "score" -> modified.scoreMultiplier
            "train" -> modified.trainMultiplier
            "atmos" -> modified.atmosMultiplier
            else -> value
        }
    }

    override fun onCalcStat(
        general: General,
        statName: String,
        value: Double,
        aux: Map<String, Any>,
    ): Double {
        val baseCtx = when (statName) {
            "leadership" -> StatContext(leadership = value)
            "strength" -> StatContext(strength = value)
            "intel" -> StatContext(intel = value)
            "criticalChance" -> StatContext(criticalChance = value)
            "dodgeChance" -> StatContext(dodgeChance = value)
            "magicChance" -> StatContext(magicChance = value)
            "warPower" -> StatContext(warPower = value)
            "bonusTrain" -> StatContext(bonusTrain = value)
            "bonusAtmos" -> StatContext(bonusAtmos = value)
            "magicTrialProb" -> StatContext(magicTrialProb = value)
            "magicSuccessProb" -> StatContext(magicSuccessProb = value)
            "magicSuccessDamage" -> StatContext(magicSuccessDamage = value)
            "dexMultiplier" -> StatContext(dexMultiplier = value)
            "expMultiplier" -> StatContext(expMultiplier = value)
            "injuryProb" -> StatContext(injuryProb = value)
            "initWarPhase" -> StatContext(initWarPhase = value)
            "sabotageDefence" -> StatContext(sabotageDefence = value)
            "dedicationMultiplier" -> StatContext(dedicationMultiplier = value)
            else -> return value
        }

        val modified = modifiers.fold(baseCtx) { stat, modifier -> modifier.onCalcStat(stat) }
        return when (statName) {
            "leadership" -> modified.leadership
            "strength" -> modified.strength
            "intel" -> modified.intel
            "criticalChance" -> modified.criticalChance
            "dodgeChance" -> modified.dodgeChance
            "magicChance" -> modified.magicChance
            "warPower" -> modified.warPower
            "bonusTrain" -> modified.bonusTrain
            "bonusAtmos" -> modified.bonusAtmos
            "magicTrialProb" -> modified.magicTrialProb
            "magicSuccessProb" -> modified.magicSuccessProb
            "magicSuccessDamage" -> modified.magicSuccessDamage
            "dexMultiplier" -> modified.dexMultiplier
            "expMultiplier" -> modified.expMultiplier
            "injuryProb" -> modified.injuryProb
            "initWarPhase" -> modified.initWarPhase
            "sabotageDefence" -> modified.sabotageDefence
            "dedicationMultiplier" -> modified.dedicationMultiplier
            else -> value
        }
    }
}

/**
 * Build the pre-turn trigger list for a general.
 * Legacy: TurnExecutionHelper::preprocessCommand()
 */
fun buildPreTurnTriggers(
    general: General,
    modifiers: List<ActionModifier> = emptyList(),
): List<GeneralTrigger> {
    val triggers = mutableListOf<GeneralTrigger>()

    // Always-present triggers
    triggers.add(InjuryReductionTrigger(general))
    triggers.add(TroopConsumptionTrigger(general))

    if (modifiers.isNotEmpty()) {
        triggers.add(ModifierBridgeTrigger(general, modifiers))
    }

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
