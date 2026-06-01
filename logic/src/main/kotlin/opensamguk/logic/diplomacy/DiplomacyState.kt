package opensamguk.logic.diplomacy

/**
 * Foreign-keyed diplomacy state codes — faithful port of PHP DIPLOMACY_STATE constants.
 *
 * PHP V1 stores state as integer codes in the `diplomacy.state` column:
 *   0 = war (교전), 1 = declaration (선전포고), 2 = trade (통상), 7 = non-aggression (불가침).
 *
 * These values are the grand-truth keys used in:
 *   - DB `diplomacy.state`
 *   - `DisallowDiplomacyBetweenStatus` / `AllowDiplomacyBetweenStatus` constraint maps
 *   - `DiplomacyMonthProcessor` state-machine transitions
 */
object DiplomacyState {
    const val WAR = 0
    const val DECLARATION = 1
    const val TRADE = 2
    const val NON_AGGRESSION = 7
}

/**
 * Time constants for diplomacy term calculations — faithful port of PHP diplomacy defaults.
 *
 * - DEFAULT_DECLARE_WAR_TERM: 선전포고 후 전환까지의 기본 턴 수 (24개월)
 * - DEFAULT_WAR_TERM: 선전포고 만료 후 전쟁 기본 턴 수 (6개월)
 * - MAX_WAR_TERM: 전쟁 최대 가능 턴 수 (13개월)
 * - MIN_NON_AGGRESSION_MONTHS: 불가침 제의 시 최소 보장 기간 (6개월)
 */
object DiplomacyConst {
    const val DEFAULT_DECLARE_WAR_TERM = 24
    const val DEFAULT_WAR_TERM = 6
    const val MAX_WAR_TERM = 13
    const val MIN_NON_AGGRESSION_MONTHS = 6
}
