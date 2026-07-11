package opensamguk.logic.scenario

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionModuleSource
import opensamguk.logic.war.WarUnit as ConcreteWarUnit
import opensamguk.logic.war.WarUnitCity
import opensamguk.logic.war.trigger.WarUnit
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import opensamguk.logic.war.trigger.triggers.CheJeonmyeolsiPhaseJeunga

object ScenarioEffectRegistry : GeneralActionModuleSource {
    private val byCode: Map<String, GeneralActionModule> = linkedMapOf(
        "event_StrongAttacker" to EventStrongAttacker,
        "event_UnlimitedDefenceThresholdChange" to EventUnlimitedDefenceThresholdChange,
        "event_MoreEffect" to EventMoreEffect,
    )

    override fun resolve(code: String?): GeneralActionModule? {
        if (code.isNullOrBlank() || code == "None") return null
        return byCode[code]
    }
}

object EventStrongAttacker : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        if (unit is WarUnitCity) return 1.0 to 1.0
        if ((unit as? ConcreteWarUnit)?.getOppose() is WarUnitCity) return 1.0 to 1.0
        return if (unit.isAttacker()) 1.4 to 0.7143 else 1.0 to 1.0
    }

    override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double = if (actionKey == "changeDefenceTrain") 0.0 else value

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheJeonmyeolsiPhaseJeunga(unit))
}

object EventUnlimitedDefenceThresholdChange : GeneralActionModule {
    override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double = if (actionKey == "changeDefenceTrain") 0.0 else value
}

object EventMoreEffect : GeneralActionModule {
    private val scoreTypes = setOf("상업", "농업", "치안", "기술", "성벽", "수비", "인구", "민심")

    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> =
        if (unit.isAttacker()) 1.4 to 0.7143 else 1.0 to 1.0

    override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double {
        if (actionKey == "changeDefenceTrain") return 0.0
        if (varType == "score" && actionKey in scoreTypes) return value * 2
        return value
    }

    override fun onCalcNationalIncome(general: General, type: String, value: Double): Double {
        if (type == "gold") return value * 2
        if (type == "rice") return value * 2
        if (type == "pop" && value > 0) return value * 2
        return value
    }

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheJeonmyeolsiPhaseJeunga(unit))
}
