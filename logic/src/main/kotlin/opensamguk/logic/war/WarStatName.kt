package opensamguk.logic.war

/**
 * The battle-stat keys threaded through `GeneralActionModule.onCalcStat` / `onCalcOpposeStat`
 * (and, for `criticalDamageRange`, the pair-typed `onCalcStatRange`) during a battle.
 *
 * Port target = PHP grand truth (literal `statName` strings + cross-vs-single classification):
 *   - `warAvoidRatio`        — `WarUnitGeneral.php:144-145` (getComputedAvoidRatio), CROSS
 *   - `warCriticalRatio`     — `WarUnitGeneral.php:131-132` (getComputedCriticalRatio), CROSS
 *   - `warMagicTrialProb`    — `che_계략시도.php:40-41`, CROSS
 *   - `warMagicSuccessProb`  — `che_계략시도.php:62-63`, CROSS
 *   - `warMagicSuccessDamage`— `che_계략시도.php:74-75` (aux = magic name, MULTIPLICATIVE), CROSS
 *   - `warMagicFailDamage`   — `che_계략실패.php:29-30` / `che_계략발동.php:29-30`, CROSS
 *   - `bonusTrain`           — `WarUnitGeneral.php:108-109` (getComputedTrain), CROSS
 *   - `bonusAtmos`           — `WarUnitGeneral.php:118-119` (getComputedAtmos), CROSS
 *   - `dex{armType}`         — `WarUnitGeneral.php:94/98` (statName = 'dex'+armType, dynamic), CROSS
 *   - `initWarPhase`         — `WarUnitGeneral.php:76` (getMaxPhase), onCalcStat-ONLY (oppose
 *                              not yet known at maxPhase calc)
 *   - `criticalDamageRange`  — `WarUnit.php:443` ([min,max] PAIR; the `onCalcStatRange` hook),
 *                              onCalcStat-ONLY
 *   - `killRice`             — `WarUnitGeneral.php:277` (finishBattle rice), onCalcStat-ONLY
 *   - `injuryProb`           — `ActionItem/che_부적_태현청생부.php:26`, onCalcStat-ONLY
 *   - `addDex`               — `General.php:443`, onCalcStat-ONLY
 *
 * Decision #1 (plan AREA F4): `criticalDamageRange` is the ONLY pair-typed key — it routes
 * through `onCalcStatRange`, NOT the scalar `onCalcStat`. The scalar war keys keep the scalar
 * hook (the 1235 GREEN P2/P3 tests keep compiling). The `dex{armType}` key is built dynamically
 * (`'dex'+armType`, e.g. dex0..dex6); `addDex` is a separate literal key (NOT a dex-family member).
 */
object WarStatName {
    const val WAR_AVOID_RATIO = "warAvoidRatio"
    const val WAR_CRITICAL_RATIO = "warCriticalRatio"
    const val WAR_MAGIC_TRIAL_PROB = "warMagicTrialProb"
    const val WAR_MAGIC_SUCCESS_PROB = "warMagicSuccessProb"
    const val WAR_MAGIC_SUCCESS_DAMAGE = "warMagicSuccessDamage"
    const val WAR_MAGIC_FAIL_DAMAGE = "warMagicFailDamage"
    const val CRITICAL_DAMAGE_RANGE = "criticalDamageRange"
    const val INIT_WAR_PHASE = "initWarPhase"
    const val BONUS_TRAIN = "bonusTrain"
    const val BONUS_ATMOS = "bonusAtmos"
    const val KILL_RICE = "killRice"
    const val INJURY_PROB = "injuryProb"
    const val ADD_DEX = "addDex"

    /** The dynamic `dex{armType}` prefix — `statName = 'dex' + armType` (WarUnitGeneral.php:94). */
    const val DEX_PREFIX = "dex"

    /** Every literal (non-dynamic) battle-stat key. The `dex{armType}` family is classified separately. */
    val ALL_KEYS: Set<String> = linkedSetOf(
        WAR_AVOID_RATIO,
        WAR_CRITICAL_RATIO,
        WAR_MAGIC_TRIAL_PROB,
        WAR_MAGIC_SUCCESS_PROB,
        WAR_MAGIC_SUCCESS_DAMAGE,
        WAR_MAGIC_FAIL_DAMAGE,
        CRITICAL_DAMAGE_RANGE,
        INIT_WAR_PHASE,
        BONUS_TRAIN,
        BONUS_ATMOS,
        KILL_RICE,
        INJURY_PROB,
        ADD_DEX,
    )

    /**
     * Keys that fold BOTH `onCalcStat` (owner side) THEN `onCalcOpposeStat` (opponent side) —
     * the cross-order fold (research Unit 2). The `dex{armType}` family also crosses (see [isCross]).
     */
    private val CROSS_KEYS: Set<String> = linkedSetOf(
        WAR_AVOID_RATIO,
        WAR_CRITICAL_RATIO,
        WAR_MAGIC_TRIAL_PROB,
        WAR_MAGIC_SUCCESS_PROB,
        WAR_MAGIC_SUCCESS_DAMAGE,
        WAR_MAGIC_FAIL_DAMAGE,
        BONUS_TRAIN,
        BONUS_ATMOS,
    )

    /** Build the dynamic dex key for a crew armType (`'dex' + armType`, WarUnitGeneral.php:94). */
    fun dexKey(armType: Int): String = DEX_PREFIX + armType

    /** True when the key is a dynamic `dex{armType}` member (e.g. dex0..dex6); excludes [ADD_DEX]. */
    fun isDexKey(statName: String): Boolean =
        statName.length > DEX_PREFIX.length &&
            statName.startsWith(DEX_PREFIX) &&
            statName != ADD_DEX &&
            statName.substring(DEX_PREFIX.length).toIntOrNull() != null

    /** True when [statName] is a known literal battle key OR a dynamic dex key. */
    fun isKnown(statName: String): Boolean = statName in ALL_KEYS || isDexKey(statName)

    /**
     * True when the key folds the cross-order pair (self `onCalcStat` THEN oppose `onCalcOpposeStat`).
     * The `dex{armType}` family crosses (WarUnitGeneral.php:94/98). Single-sided keys
     * (initWarPhase/criticalDamageRange/killRice/injuryProb/addDex) fold `onCalcStat` ONLY.
     */
    fun isCross(statName: String): Boolean = statName in CROSS_KEYS || isDexKey(statName)

    /** True for the ONLY pair-typed key, `criticalDamageRange` (decision #1). */
    fun isPair(statName: String): Boolean = statName == CRITICAL_DAMAGE_RANGE
}
