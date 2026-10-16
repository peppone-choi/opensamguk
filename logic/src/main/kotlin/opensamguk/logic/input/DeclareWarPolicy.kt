package opensamguk.logic.input

import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.economy.Resources

/**
 * PROVISIONAL — 사용자 임시 결정 2026-10-16 (Sentinel_5e004a71…/Sentinel_ce16c479…). 변경 시 이 파일과 DeclareWarPolicyTest만 수정.
 * No additional resource debit is made by the court executor.
 */
object DeclareWarPolicy {
    val RESOURCE_COST = Resources()
    const val DECLARATION_TERM_MONTHS = DiplomacyConst.DEFAULT_DECLARE_WAR_TERM
    const val ALLOW_DURING_NON_AGGRESSION = true
}
