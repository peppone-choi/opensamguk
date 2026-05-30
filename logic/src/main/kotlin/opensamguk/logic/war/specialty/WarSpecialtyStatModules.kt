package opensamguk.logic.war.specialty

import opensamguk.common.constants.GameUnitConst
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.war.WarStatName
import opensamguk.logic.war.specialty.triggers.CheBangyeBaldong
import opensamguk.logic.war.specialty.triggers.CheBangyeSido
import opensamguk.logic.war.specialty.triggers.CheBusangMuhyo
import opensamguk.logic.war.specialty.triggers.CheFilsalGanghwaHoipibulga
import opensamguk.logic.war.specialty.triggers.CheJeontuChiryoBaldong
import opensamguk.logic.war.specialty.triggers.CheJeontuChiryoSido
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.WarUnit
import opensamguk.logic.war.trigger.WarUnitTriggerCaller

/**
 * AW2 — the stat-delta war specialties (magic / critical / avoid `onCalcStat` / `onCalcOpposeStat` folds),
 * faithful ports of PHP `ActionSpecialWar/che_*.php`. Each registers into [SpecialWarRegistry] by id.
 *
 * Cross-order discipline (research Unit 2 / F5): a specialty's OWNER-side delta lives in [onCalcStat], its
 * OPPONENT-side delta in [onCalcOpposeStat]; the pipeline folds owner-then-oppose so MULTIPLICATIVE magic
 * keys (warMagicSuccessDamage) accumulate in PHP order. `criticalDamageRange` is the ONLY pair key and
 * routes through [onCalcStatRange] (decision #1). ZERO RNG in any hook (the draws live in the injected
 * triggers + the phase machine).
 *
 * The dex-fold specialties (귀병/궁병) add `getVar('dex{armType}')` when the `dex{opposeType.armType}` (attack)
 * / `dex{myArmType}` (defence) key matches — PHP `str_starts_with($statName,'dex')` branch. The aux carries
 * `isAttacker` + `opposeType` (the crewType), mirroring `che_궁병.php:34-44`.
 */

/** che_필살 (id 71) — `che_필살.php`. warCriticalRatio +0.30; criticalDamageRange → [(min+max)/2, max]; injects 필살강화_회피불가. */
object ChePilsal : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.WAR_CRITICAL_RATIO) value + 0.30 else value

    override fun onCalcStatRange(
        general: General,
        statName: String,
        value: Pair<Double, Double>,
        aux: Map<String, Any?>,
    ): Pair<Double, Double> {
        if (statName != WarStatName.CRITICAL_DAMAGE_RANGE) return value
        val (rangeMin, rangeMax) = value
        return (rangeMin + rangeMax) / 2 to rangeMax
    }

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheFilsalGanghwaHoipibulga(unit))
}

/** che_견고 (id 62) — `che_견고.php`. oppose warMagicSuccessProb −0.1 / warCriticalRatio −0.20; injects 부상무효 (DEDUP*404); getWarPowerMultiplier [1, 0.9]. */
object CheGyeongo : GeneralActionModule {
    private val dedupRaiseType: Int = BaseWarUnitTrigger.TYPE_NONE + BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE * 404

    override fun onCalcOpposeStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        val debuff = when (statName) {
            WarStatName.WAR_MAGIC_SUCCESS_PROB -> 0.1
            WarStatName.WAR_CRITICAL_RATIO -> 0.20
            else -> 0.0
        }
        return value - debuff
    }

    override fun getBattleInitSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheBusangMuhyo(unit, dedupRaiseType))

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheBusangMuhyo(unit, dedupRaiseType))

    override fun getWarPowerMultiplier(unit: WarUnit): Pair<Double, Double> = 1.0 to 0.9
}

/** che_신중 (id 44) — `che_신중.php`. warMagicSuccessProb +1 → guaranteed (nextBool(≥1) short-circuits NO-draw). */
object CheSinjung : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.WAR_MAGIC_SUCCESS_PROB) value + 1 else value
}

/** che_집중 (id 43) — `che_집중.php`. warMagicSuccessDamage ×1.5 (MULTIPLICATIVE). */
object CheJipjung : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.WAR_MAGIC_SUCCESS_DAMAGE) value * 1.5 else value
}

/** che_환술 (id 42) — `che_환술.php`. warMagicSuccessProb +0.1; warMagicSuccessDamage ×1.3. */
object CheHwansul : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double = when (statName) {
        WarStatName.WAR_MAGIC_SUCCESS_PROB -> value + 0.1
        WarStatName.WAR_MAGIC_SUCCESS_DAMAGE -> value * 1.3
        else -> value
    }
}

/** che_신산 (id 41) — `che_신산.php`. warMagicTrialProb +0.2, warMagicSuccessProb +0.2; domestic 계략 success +0.1. */
object CheSinsan : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double = when (statName) {
        WarStatName.WAR_MAGIC_TRIAL_PROB -> value + 0.2
        WarStatName.WAR_MAGIC_SUCCESS_PROB -> value + 0.2
        else -> value
    }

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
        if (actionKey == "계략" && varType == "success") value + 0.1 else value
}

/** che_의술 (id 73) — `che_의술.php`. injects 전투치료시도(PRE+350)/전투치료발동(POST+550). (pre-turn 도시치료 is out of battle scope.) */
object CheUisul : GeneralActionModule {
    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheJeontuChiryoSido(unit), CheJeontuChiryoBaldong(unit))
}

/** che_귀병 (id 40) — `che_귀병.php`. warMagicSuccessProb +0.2; dex-fold (귀병 숙련 가산); domestic cost −10%. */
object CheGwibyeong : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == WarStatName.WAR_MAGIC_SUCCESS_PROB) return value + 0.2
        return dexFold(general, statName, value, aux, GameUnitConst.T_WIZARD)
    }

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
        recruitDexCostBuff(actionKey, varType, value, aux, GameUnitConst.T_WIZARD)
}

/** che_궁병 (id 51) — `che_궁병.php`. warAvoidRatio +0.2; dex-fold (궁병 숙련 가산); domestic cost −10%. */
object CheGungbyeong : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == WarStatName.WAR_AVOID_RATIO) return value + 0.2
        return dexFold(general, statName, value, aux, GameUnitConst.T_ARCHER)
    }

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double =
        recruitDexCostBuff(actionKey, varType, value, aux, GameUnitConst.T_ARCHER)
}

/** che_반계 (id 45) — `che_반계.php`. self warMagicSuccessDamage +0.9 iff aux=='반목'; oppose warMagicSuccessProb −0.1; injects 반계시도/반계발동. */
object CheBangye : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.WAR_MAGIC_SUCCESS_DAMAGE && aux["magic"] == "반목") value + 0.9 else value

    override fun onCalcOpposeStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == WarStatName.WAR_MAGIC_SUCCESS_PROB) value - 0.1 else value

    override fun getBattlePhaseSkillTriggerList(unit: WarUnit): WarUnitTriggerCaller =
        WarUnitTriggerCaller(CheBangyeSido(unit), CheBangyeBaldong(unit))
}

/** che_징병 (id 72) — `che_징병.php`. leadership +25% pure-stat boost; domestic train/atmos overrides + 징집인구 score 0. */
object CheJingbyeong : GeneralActionModule {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == "leadership") value + general.leadership * 0.25 else value

    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey == "징병" || actionKey == "모병") {
            if (varType == "train" || varType == "atmos") {
                return if (actionKey == "징병") 70.0 else 84.0
            }
        }
        if (actionKey == "징집인구" && varType == "score") return 0.0
        return value
    }
}

// --- shared dex / recruit-cost helpers (귀병/궁병/기병/보병/공성 — `che_*.php` dex branch) ---------------------

/**
 * PHP `che_*.php` dex branch (e.g. `che_궁병.php:34-44`): when `str_starts_with($statName,'dex')`, the
 * attacker adds `getVar('dex{myArmType}')` to the `dex{opposeType.armType}` key; the defender adds it to the
 * `dex{myArmType}` key. `aux['isAttacker']` + `aux['opposeType']` (the opposing crewType, carrying `.armType`).
 */
internal fun dexFold(
    general: General,
    statName: String,
    value: Double,
    aux: Map<String, Any?>,
    myArmType: Int,
): Double {
    if (!statName.startsWith(WarStatName.DEX_PREFIX) || !WarStatName.isDexKey(statName)) return value
    val isAttacker = aux["isAttacker"] as? Boolean ?: return value
    // F2 threads aux['opposeType'] = the opposing crewType (GameUnitDetail), carrying `.armType`.
    val opposeArmType = (aux["opposeType"] as? opensamguk.common.constants.GameUnitDetail)?.armType ?: return value
    val myDexKey = WarStatName.dexKey(myArmType)
    val myDexValue = generalDexVar(general, myArmType)
    if (isAttacker && WarStatName.dexKey(opposeArmType) == statName) return value + myDexValue
    if (!isAttacker && myDexKey == statName) return value + myDexValue
    return value
}

/** PHP `$general->getVar('dex{armType}')` — the general's per-armType dexterity accumulator (meta key). */
internal fun generalDexVar(general: General, armType: Int): Double =
    (general.meta[WarStatName.dexKey(armType)] as? Number)?.toDouble() ?: 0.0

/** PHP `che_*.php::onCalcDomestic` recruit-cost branch: 징병/모병 cost ×0.9 when `aux['armType']` matches. */
internal fun recruitDexCostBuff(
    actionKey: String,
    varType: String,
    value: Double,
    aux: Map<String, Any?>,
    myArmType: Int,
): Double {
    if (actionKey == "징병" || actionKey == "모병") {
        if (varType == "cost" && (aux["armType"] as? Int) == myArmType) return value * 0.9
    }
    return value
}
