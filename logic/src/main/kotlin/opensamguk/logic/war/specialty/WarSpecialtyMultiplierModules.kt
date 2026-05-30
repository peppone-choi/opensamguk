package opensamguk.logic.war.specialty

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitConstraint
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.war.WarStatName
import opensamguk.logic.war.WarUnitCity
import opensamguk.logic.war.WarUnitGeneral
import opensamguk.logic.war.specialty.triggers.CheDolgyeokJisok
import opensamguk.logic.war.specialty.triggers.CheGyeoknoBaldong
import opensamguk.logic.war.specialty.triggers.CheGyeoknoSido
import opensamguk.logic.war.specialty.triggers.CheJeogyeokBaldong
import opensamguk.logic.war.specialty.triggers.CheJeogyeokSido
import opensamguk.logic.war.specialty.triggers.CheWiapBaldong
import opensamguk.logic.war.specialty.triggers.CheWiapSido
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.WarUnit
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import opensamguk.logic.war.WarUnit as ConcreteWarUnit
import kotlin.math.ln
import kotlin.math.max

/**
 * AW3 — the warpower-multiplier + trigger-injecting war specialties (`ActionSpecialWar/che_*.php`
 * `getWarPowerMultiplier` + `getBattleInit/PhaseSkillTriggerList`). Each registers into [SpecialWarRegistry]
 * by id.
 *
 * `getWarPowerMultiplier(unit)` reads BATTLE STATE (PHP `$unit->hasActivatedSkillOnLog(...)`,
 * `$unit->getGeneral()->getRankVar(...)`, `$unit->getOppose() instanceof WarUnitCity`, `$unit->isAttacker()`).
 * The F5 hook threads the F1 trigger contract [WarUnit]; the concrete unit IS the F2 [ConcreteWarUnit] /
 * [WarUnitGeneral] at runtime, so we downcast for the state methods the minimal contract omits. The pipeline
 * folds `[att,def]` MULTIPLICATIVELY over the 12-source stack (F5). ZERO RNG in any hook (the draws live in
 * the injected triggers + the phase machine).
 */

/** che_격노 (id 74) — `che_격노.php`. getWarPowerMultiplier [1+0.2*격노cnt, 1] (cross-phase log count); injects 격노시도/격노발동. */
object CheGyeokno : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        val activatedCnt = (unit as? ConcreteWarUnit)?.hasActivatedSkillOnLog("격노") ?: 0
        return (1 + 0.2 * activatedCnt) to 1.0
    }

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheGyeoknoSido(unit), CheGyeoknoBaldong(unit))
}

/** che_무쌍 (id 61) — `che_무쌍.php`. warCriticalRatio +0.1 if attacker; getWarPowerMultiplier log2(killnum/5)-scaled. */
object CheMussang : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == WarStatName.WAR_CRITICAL_RATIO && (aux["isAttacker"] as? Boolean == true)) {
            return value + 0.1
        }
        return value
    }

    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        var attackMultiplier = 1.05
        var defenceMultiplier = 0.98
        val killnum = (unit as? WarUnitGeneral)?.state?.getRank("killnum") ?: 0
        // PHP `log(max(1, $killnum/5), 2)` — base-2 log of max(1, killnum/5).
        val logTerm = log2(max(1.0, killnum / 5.0))
        attackMultiplier += logTerm / 20
        defenceMultiplier -= logTerm / 50
        return attackMultiplier to defenceMultiplier
    }
}

/** che_공성 (id 53) — `che_공성.php`. getWarPowerMultiplier [2,1] vs City; dex-fold; domestic cost −10%. */
object CheGongseong : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        val oppose = (unit as? ConcreteWarUnit)?.getOppose()
        return if (oppose is WarUnitCity) 2.0 to 1.0 else 1.0 to 1.0
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        dexFold(general, statName, value, aux, GameUnitConst.T_SIEGE)

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
        recruitDexCostBuff(actionKey, varType, value, aux, GameUnitConst.T_SIEGE)
}

/** che_기병 (id 52) — `che_기병.php`. getWarPowerMultiplier attacker [1.2,1] / defender [1.1,1]; dex-fold; domestic. */
object CheGibyeong : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> =
        if (unit.isAttacker()) 1.2 to 1.0 else 1.1 to 1.0

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        dexFold(general, statName, value, aux, GameUnitConst.T_CAVALRY)

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
        recruitDexCostBuff(actionKey, varType, value, aux, GameUnitConst.T_CAVALRY)
}

/** che_보병 (id 50) — `che_보병.php`. getWarPowerMultiplier attacker [1,0.9] / defender [1,0.8]; dex-fold; domestic. */
object CheBobyeong : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> =
        if (unit.isAttacker()) 1.0 to 0.9 else 1.0 to 0.8

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        dexFold(general, statName, value, aux, GameUnitConst.T_FOOTMAN)

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
        recruitDexCostBuff(actionKey, varType, value, aux, GameUnitConst.T_FOOTMAN)
}

/** che_돌격 (id 60) — `che_돌격.php`. initWarPhase +2; getWarPowerMultiplier [1.05,1] if attacker; injects 돌격지속. */
object CheDolgyeok : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.INIT_WAR_PHASE) value + 2 else value

    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> =
        if (unit.isAttacker()) 1.05 to 1.0 else 1.0 to 1.0

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheDolgyeokJisok(unit))
}

/** che_저격 (id 70) — `che_저격.php`. injects 저격시도(unit, TYPE_NONE, 0.5, 20, 40)(PRE+100) / 저격발동(POST+100). */
object CheJeogyeok : GeneralActionModule {
    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(
            CheJeogyeokSido(unit, BaseWarUnitTrigger.TYPE_NONE, 0.5, 20.0, 40.0),
            CheJeogyeokBaldong(unit),
        )
}

/** che_위압 (id 63) — `che_위압.php`. injects 위압시도(BEGIN+100) / 위압발동(POST+700). */
object CheWiap : GeneralActionModule {
    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheWiapSido(unit), CheWiapBaldong(unit))
}

/** che_척사 (id 75) — `che_척사.php`. getWarPowerMultiplier [1.2,0.8] vs region/city crewType, else [1,1]. */
object CheCheoksa : GeneralActionModule {
    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> {
        val oppose = (unit as? ConcreteWarUnit)?.getOppose() ?: return 1.0 to 1.0
        val crew = oppose.getCrewType()
        val reqCities = crew.reqConstraints.any { it is UnitConstraint.ReqCities }
        val reqRegions = crew.reqConstraints.any { it is UnitConstraint.ReqRegions }
        return if (reqCities || reqRegions) 1.2 to 0.8 else 1.0 to 1.0
    }
}

/** PHP `log($x, 2)` — base-2 logarithm. */
private fun log2(x: Double): Double = ln(x) / ln(2.0)
