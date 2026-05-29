package opensamguk.logic.stats

import opensamguk.logic.domain.General

/**
 * One of the 9 action-stack sources (nation-type / officer / domestic-special / war-special /
 * personality / crew / inheritance / scenario / item). P1 wires ZERO modules — identity fold.
 *
 * Port target = PHP `General.php` fold dispatchers (815-875), all `iAction` hooks default to identity
 * (`DefaultAction.php`). The `$aux` param (PHP threads `['armType'=>…]`) is a default-empty Map here so
 * the P1 call sites keep compiling unchanged.
 */
interface GeneralActionModule {
    /** onCalcStat(general, statName, value, aux) -> value. Default identity. PHP General.php:827-838. */
    fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?> = emptyMap()): Double = value

    /** onCalcDomestic(actionKey, varType['cost'|'score'|'success'|'fail'], value, aux) -> value. PHP :815-825. */
    fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?> = emptyMap()): Double = value

    /** onCalcStrategic(turnType, varType['delay'|'globalDelay'], value) -> value. PHP :853-863. */
    fun onCalcStrategic(general: General, varType: String, value: Double): Double = value

    /**
     * onCalcNationalIncome(type['gold'|'rice'|'pop'], amount) -> amount. PHP :865-875.
     * NOTE: the only call site folds this over the NATION-TYPE source ONLY (ProcessSemiAnnual.php:46),
     * never over the full general stack — see [GeneralActionPipeline.nationIncomeFold].
     */
    fun onCalcNationalIncome(general: General, type: String, value: Double): Double = value

    /** onCalcOpposeStat(general, statName, value, aux) -> value. War-adjacency stub. PHP :840-851. */
    fun onCalcOpposeStat(general: General, statName: String, value: Double, aux: Map<String, Any?> = emptyMap()): Double = value

    /**
     * onCalcStatRange(general, statName, value, aux) -> value. Pair-typed sibling of [onCalcStat]
     * (P4 decision #1): the ONLY pair key is `criticalDamageRange` ([min,max]), which PHP threads
     * through `onCalcStat` as an array (`WarUnit.php:443`; `che_필살` rewrites it to
     * `[(min+max)/2, max]`). The scalar [onCalcStat] signature is NOT widened — every P2/P3 module
     * keeps compiling unchanged. Default identity. `criticalDamageRange` is onCalcStat-ONLY (no
     * oppose cross), so there is no pair-typed onCalcOpposeStat.
     */
    fun onCalcStatRange(
        general: General,
        statName: String,
        value: Pair<Double, Double>,
        aux: Map<String, Any?> = emptyMap(),
    ): Pair<Double, Double> = value
}

class GeneralActionPipeline(private val modules: List<GeneralActionModule> = emptyList()) {
    fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?> = emptyMap()): Double =
        modules.fold(value) { acc, m -> m.onCalcStat(general, statName, acc, aux) }

    fun onCalcDomestic(general: General, actionKey: String, varType: String, value: Double, aux: Map<String, Any?> = emptyMap()): Double =
        modules.fold(value) { acc, m -> m.onCalcDomestic(general, actionKey, varType, acc, aux) }

    fun onCalcStrategic(general: General, turnType: String, varType: String, value: Double): Double =
        modules.fold(value) { acc, m -> m.onCalcStrategic(general, varType, acc) }

    fun onCalcOpposeStat(general: General, statName: String, value: Double, aux: Map<String, Any?> = emptyMap()): Double =
        modules.fold(value) { acc, m -> m.onCalcOpposeStat(general, statName, acc, aux) }

    /**
     * Left-fold of the pair-typed [GeneralActionModule.onCalcStatRange] over the SAME module list
     * as the scalar [onCalcStat] (P4 decision #1). Only `criticalDamageRange` uses this path; the
     * fold order is the identical `getActionList` 12-source order so the pair rewrite (che_필살)
     * accumulates draw-order-faithfully.
     */
    fun onCalcStatRange(general: General, statName: String, value: Pair<Double, Double>, aux: Map<String, Any?> = emptyMap()): Pair<Double, Double> =
        modules.fold(value) { acc, m -> m.onCalcStatRange(general, statName, acc, aux) }

    /**
     * National income ASYMMETRY (research Unit 12): income is folded over the NATION-TYPE source ONLY.
     * PHP `Event/Action/ProcessSemiAnnual.php:46` calls `$nationTypeObj->onCalcNationalIncome('pop', $popRatio)`
     * directly on the nation-type object — it does NOT go through `$general->onCalcNationalIncome` (the general
     * stack fold). This is a SEPARATE path that ignores the general pipeline entirely; a null nation-type source
     * is identity.
     */
    fun nationIncomeFold(nationTypeModule: GeneralActionModule?, type: String, value: Double, general: General? = null): Double =
        nationTypeModule?.onCalcNationalIncome(general ?: PLACEHOLDER_GENERAL, type, value) ?: value

    companion object {
        /**
         * The PHP `getActionList()` fold order (General.php:787-799): array_merge of the 8 single slots
         * followed by the 4 item slots (`itemObjs` = [horse, weapon, book, item]). Documented so module
         * registration (S-MODULES) can pin the exact stack order; nulls are skipped during a fold.
         */
        val MODULE_ORDER: List<String> = listOf(
            "nationType", "officer", "specialDomestic", "specialWar",
            "personality", "crew", "inherit", "scenario",
            "horse", "weapon", "book", "item",
        )

        /**
         * The nation-type income module's `onCalcNationalIncome` does not read the general in PHP
         * (`General` param is unused there); this placeholder is only used when no general is supplied to
         * [nationIncomeFold], preserving the identity-on-null-source contract without an NPE.
         */
        private val PLACEHOLDER_GENERAL = General(
            id = 0, nationId = 0, cityId = 0,
            leadership = 0, strength = 0, intel = 0, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 0,
            gold = 0, rice = 0,
        )
    }
}
