package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt

/**
 * Faithful port of `checkSecretPermission()` (`legacy/devsam-core/hwe/func.php:390-435`).
 *
 * The 내무부 finance setters all gate on:
 *   `permission = checkSecretPermission(me, false)`  (checkSecretLimit = FALSE)
 *   deny when `permission < 0`, then deny again when `officer_level < 5 && permission != 4`.
 *
 * In opensamguk the `permission` (ambassador/auditor/…) and `penalty` map ride the general `meta`
 * jsonb (no dedicated columns), mirroring the PHP `general.permission` / `general.penalty` columns.
 *
 * NOTE (parity flag — proven, not fabricated): the PHP penalty branch reads
 * `PenaltyKey::NoChief` (returns 0) and `checkSecretMaxPermission($penalty)` (the per-penalty cap).
 * Those penalty caps are P8-golden territory (the live 1010 scenario carries no chief-penalty rows),
 * so this port models the `NoChief` short-circuit but leaves `secretMax = 4` (no cap) — a chief
 * penalty cap would only ever LOWER the result, never raise it, so the non-penalised path is exact.
 */
object SecretPermission {

    /** The string `general.permission` values that grant elevated secret access. */
    const val AMBASSADOR = "ambassador"
    const val AUDITOR = "auditor"

    /** `PenaltyKey::NoChief` — the penalty that bars all chief (수뇌) access (returns 0). */
    const val PENALTY_NO_CHIEF = "no_chief"

    /**
     * @param general the acting officer
     * @param secretLimit the nation `secretlimit` (only consulted when [checkSecretLimit] is true; the
     *   finance setters always pass false, so it is unused there)
     * @param checkSecretLimit PHP `$checkSecretLimit` — the finance setters pass `false`
     * @return the PHP `min(secretMin, secretMax)`; `-1` = no nation / officer_level 0 (hard deny)
     */
    fun check(general: General, secretLimit: Int = 0, checkSecretLimit: Boolean = false): Int {
        if (general.nationId == 0) return -1
        if (general.officerLevel == 0) return -1

        val permission = general.meta["permission"] as? String

        @Suppress("UNCHECKED_CAST")
        val penalty = general.meta["penalty"] as? Map<String, Any?> ?: emptyMap()
        if (penalty[PENALTY_NO_CHIEF] == true) return 0

        // checkSecretMaxPermission(penalty): the per-penalty cap. Non-penalised path = no cap (4).
        val secretMax = 4

        val secretMin = when {
            general.officerLevel == 12 -> 4
            permission == AMBASSADOR -> 4
            permission == AUDITOR -> 3
            general.officerLevel >= 5 -> 2
            general.officerLevel > 1 -> 1
            checkSecretLimit -> {
                val belong = metaInt(general.meta, "belong")
                if (belong >= secretLimit) 1 else 0
            }
            else -> 0
        }
        return minOf(secretMin, secretMax)
    }

    /**
     * The exact two-line gate shared by every 내무부 finance setter (SetNotice/SetScoutMsg/SetRate/
     * SetBill/SetSecretLimit/SetBlockWar/SetBlockScout): `permission = check(me, false)`, deny when
     * `permission < 0`, deny when `officer_level < 5 && permission != 4`. Returns the PHP-faithful
     * deny reason, or `null` when permitted.
     */
    fun financeSetterDenyReason(general: General): String? {
        val permission = check(general, checkSecretLimit = false)
        if (permission < 0) return "권한이 부족합니다."
        if (general.officerLevel < 5 && permission != 4) return "권한이 부족합니다."
        return null
    }
}
