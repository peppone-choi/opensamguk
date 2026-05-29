package opensamguk.logic.traits

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionModuleSource

/**
 * Source #5 of the 9-source action stack — the 12 personalities (the `ActionPersonality` package),
 * constructed in `General.php:117` from the general's `personal` code.
 *
 * Each PHP class `implements iAction; use DefaultAction;` and overrides at most two hooks:
 *   - `onCalcStat`     (statName ∈ experience / dedication / bonusTrain / bonusAtmos)
 *   - `onCalcDomestic` (turnType ∈ 징병 / 모병 / 단련, varType ∈ cost / success)
 * everything else inherits `DefaultAction` identity. `bonusTrain` / `bonusAtmos` are WAR-init
 * stat-stack entries (the ±5 deltas flow into the war-side troop init); the deltas are ported
 * here faithfully even though the only P2-domestic consumer of `onCalcStat` is experience /
 * dedication (`StatChange.kt`).
 *
 * NAME / INFO are the literal PHP `$name` / `$info` (DefaultAction::getInfo returns `$info`
 * verbatim — a comma-joined "{pros}, {cons}" string, NOT the TS '장점:X / 단점:Y' shape).
 *
 * Multiplicative vs additive: every experience/dedication/recruit-cost effect is a multiply
 * (×1.1 / ×0.9 / ×1.2 / ×0.8); the bonusTrain/bonusAtmos deltas are ±5 additions; the SINGLE
 * additive-domestic exception is 은둔's 단련 success +0.1.
 */
abstract class PersonalityModule(
    val id: Int,
    val name: String,
    val info: String,
) : GeneralActionModule

/** che_왕좌 (id 0): 명성 +10%, 사기 -5. ActionPersonality/che_왕좌.php. */
object PersonalityWangjwa : PersonalityModule(0, "왕좌", "명성 +10%, 사기 -5") {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "experience") return value * 1.1
        if (statName == "bonusAtmos") return value - 5
        return value
    }
}

/** che_대의 (id 1): 명성 +10%, 훈련 -5. ActionPersonality/che_대의.php. */
object PersonalityDaeui : PersonalityModule(1, "대의", "명성 +10%, 훈련 -5") {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "experience") return value * 1.1
        if (statName == "bonusTrain") return value - 5
        return value
    }
}

/** che_의협 (id 2): 사기 +5, 징·모병 비용 +20%. ActionPersonality/che_의협.php. */
object PersonalityUihyeop : PersonalityModule(2, "의협", "사기 +5, 징·모병 비용 +20%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in RECRUIT && varType == "cost") return value * 1.2
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "bonusAtmos") return value + 5
        return value
    }
}

/** che_패권 (id 3): 훈련 +5, 징·모병 비용 +20%. ActionPersonality/che_패권.php. */
object PersonalityPaegwon : PersonalityModule(3, "패권", "훈련 +5, 징·모병 비용 +20%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in RECRUIT && varType == "cost") return value * 1.2
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "bonusTrain") return value + 5
        return value
    }
}

/** che_정복 (id 4): 명성 -10%, 사기 +5. ActionPersonality/che_정복.php. */
object PersonalityJeongbok : PersonalityModule(4, "정복", "명성 -10%, 사기 +5") {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "experience") return value * 0.9
        if (statName == "bonusAtmos") return value + 5
        return value
    }
}

/** che_할거 (id 5): 명성 -10%, 훈련 +5. ActionPersonality/che_할거.php. */
object PersonalityHalgeo : PersonalityModule(5, "할거", "명성 -10%, 훈련 +5") {
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "experience") return value * 0.9
        if (statName == "bonusTrain") return value + 5
        return value
    }
}

/** che_출세 (id 6): 명성 +10%, 징·모병 비용 +20%. ActionPersonality/che_출세.php. */
object PersonalityChulse : PersonalityModule(6, "출세", "명성 +10%, 징·모병 비용 +20%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in RECRUIT && varType == "cost") return value * 1.2
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "experience") return value * 1.1
        return value
    }
}

/** che_재간 (id 7): 명성 -10%, 징·모병 비용 -20%. ActionPersonality/che_재간.php. */
object PersonalityJaegan : PersonalityModule(7, "재간", "명성 -10%, 징·모병 비용 -20%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in RECRUIT && varType == "cost") return value * 0.8
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "experience") return value * 0.9
        return value
    }
}

/** che_유지 (id 8): 훈련 -5, 징·모병 비용 -20%. ActionPersonality/che_유지.php. */
object PersonalityYuji : PersonalityModule(8, "유지", "훈련 -5, 징·모병 비용 -20%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in RECRUIT && varType == "cost") return value * 0.8
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "bonusTrain") return value - 5
        return value
    }
}

/** che_안전 (id 9): 사기 -5, 징·모병 비용 -20%. ActionPersonality/che_안전.php. */
object PersonalityAnjeon : PersonalityModule(9, "안전", "사기 -5, 징·모병 비용 -20%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey in RECRUIT && varType == "cost") return value * 0.8
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "bonusAtmos") return value - 5
        return value
    }
}

/**
 * che_은둔 (id 10): 명성 -10%, 계급 -10%, 사기 -5, 훈련 -5, 단련 성공률 +10%.
 * ActionPersonality/che_은둔.php. The ONLY personality touching dedication (×0.9) and the ONLY
 * additive-domestic effect (단련 success +0.1).
 */
object PersonalityEundun : PersonalityModule(10, "은둔", "명성 -10%, 계급 -10%, 사기 -5, 훈련 -5, 단련 성공률 +10%") {
    override fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?>): Double {
        if (actionKey == "단련" && varType == "success") return value + 0.1
        return value
    }

    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double {
        if (statName == "bonusAtmos") return value - 5
        if (statName == "bonusTrain") return value - 5
        if (statName == "experience") return value * 0.9
        if (statName == "dedication") return value * 0.9
        return value
    }
}

/** None (id -1): the neutral personality. ActionPersonality/None.php — inert (DefaultAction identity). */
object PersonalityNone : PersonalityModule(-1, "-", "")

/** 징병 / 모병 — the recruit turnTypes the cost multipliers gate on (PHP `in_array($turnType, ['징병','모병'])`). */
private val RECRUIT = setOf("징병", "모병")

/**
 * Resolves a general's `personal` code to its [PersonalityModule], or `null` for the inert slot
 * (null / blank / "None" code, AND any unknown code — PHP's `getPersonality` falls back to `None`
 * which folds identity, so we skip it). The factory consumes this via [GeneralActionModuleSource].
 */
class PersonalityRegistry : GeneralActionModuleSource {
    private val byCode: Map<String, PersonalityModule> = listOf(
        PersonalityWangjwa, PersonalityDaeui, PersonalityUihyeop, PersonalityPaegwon,
        PersonalityJeongbok, PersonalityHalgeo, PersonalityChulse, PersonalityJaegan,
        PersonalityYuji, PersonalityAnjeon, PersonalityEundun,
    ).associateBy { "che_${it.name}" }

    override fun resolve(code: String?): GeneralActionModule? {
        if (code.isNullOrBlank() || code == "None") return null
        return byCode[code]
    }
}
