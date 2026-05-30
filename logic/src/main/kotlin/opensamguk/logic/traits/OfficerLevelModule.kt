package opensamguk.logic.traits

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule

/**
 * Source #2 of the 9-source action stack — a faithful port of PHP `TriggerOfficerLevel`
 * (`TriggerOfficerLevel.php:4-87`, constructed in `General.php:111`
 * `new TriggerOfficerLevel($this->raw, $nation['level'])`).
 *
 * Construction (`TriggerOfficerLevel.php:11-21`):
 *   - officerLevel = general['officer_level']
 *   - DEMOTION: `if (2 <= officerLevel <= 4 && general['officer_city'] != general['city']) officerLevel = 1`
 *     (a 태수/군사/종사 stationed away from its assigned city loses its officer rank for stat purposes)
 *   - lbonus = calcLeadershipBonus(officerLevel, nationLevel)   (func_process.php:52-61)
 *
 * `calcLeadershipBonus` (`func_process.php:52-61`):
 *   officerLevel == 12 → nationLevel * 2 ; officerLevel >= 5 → nationLevel ; else 0.
 *
 * Hooks ported verbatim:
 *   - onCalcStat (`:55-60`): leadership += lbonus ; all other stats identity.
 *   - onCalcDomestic (`:27-53`): a +5% (×1.05) `score` buff gated on the (demoted) officerLevel
 *     per a per-turnType allow-list. Non-`score` varTypes are identity.
 *
 * War hooks (getWarPowerMultiplier `:62-86`) are out of P2-domestic scope; not ported here.
 */
class OfficerLevelModule(
    officerLevel: Int,
    nationLevel: Int,
    officerCity: Int,
    currentCity: Int,
) : GeneralActionModule {

    /** The effective officer level AFTER the away-from-officer-city demotion (TriggerOfficerLevel.php:16-18). */
    val officerLevel: Int =
        if (officerLevel in 2..4 && officerCity != currentCity) 1 else officerLevel

    /** calcLeadershipBonus(officerLevel, nationLevel) — func_process.php:52-61. */
    val lbonus: Int = when {
        this.officerLevel == 12 -> nationLevel * 2
        this.officerLevel >= 5 -> nationLevel
        else -> 0
    }

    /** TriggerOfficerLevel.php:55-60 — leadership gains the officer bonus; other stats unchanged. */
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == "leadership") value + lbonus else value

    /** TriggerOfficerLevel.php:27-53 — score-only ×1.05 buff per the per-turnType officer-level allow-list. */
    override fun onCalcDomestic(
        general: General,
        actionKey: String,
        varType: String,
        value: Double,
        aux: Map<String, Any?>,
    ): Double {
        if (varType != "score") return value
        val boosted = when (actionKey) {
            "농업", "상업" -> officerLevel in setOf(12, 11, 9, 7, 5, 3)
            "기술" -> officerLevel in setOf(12, 11, 9, 7, 5)
            "민심", "인구" -> officerLevel in setOf(12, 11, 2)
            "수비", "성벽", "치안" -> officerLevel in setOf(12, 11, 10, 8, 6, 4)
            else -> false
        }
        return if (boosted) value * 1.05 else value
    }
}
