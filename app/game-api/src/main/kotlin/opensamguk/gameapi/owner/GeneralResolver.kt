package opensamguk.gameapi.owner

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import org.springframework.stereotype.Service

/**
 * F2 Wave 1 — resolve a verified `userId` (JWT subject) to the game-state general they own.
 *
 * The primary link is the account-side [GeneralOwnerEntity] (V10 `general_owner`): `userId → general_id`,
 * then a READ-ONLY join to the game-state `general` row via [GeneralReadRepository] (the legitimate
 * precheck-path JPA read; never a write). Legacy-created characters, including `/api/join`, also carry
 * ownership directly on `general.user_id` (PHP `general.owner`), so that column is the read-only fallback
 * when `general_owner` is absent. Returns null when the user owns no general — the UI then shows
 * 장수선택/빙의.
 *
 * [ResolvedGeneral] exposes exactly the fields MainControlBar gating keys on
 * (officer_level/permission/nation_id/nation level), derived PHP-faithfully:
 *   * `officerLevel`   = `general.officer_level`
 *   * `permission`     = derived from `officer_level` (legacy `getNationPermission`: 0=일반,
 *                        1=관직자(level≥2), 2=수뇌(level≥5)). The JWT does NOT carry game permission,
 *                        so game-api derives it from the resolved general (OQ4 ruling).
 *   * `nationId`       = `general.nation_id` (0 = 재야/no nation)
 *   * `nationLevel`    = `nation.level` for that nation (0 when 재야 / nation missing)
 */
@Service
class GeneralResolver(
    private val owners: GeneralOwnerRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
) {
    /**
     * The resolved possession view + gating fields. `permission`/`nationLevel` are DERIVED, not stored.
     */
    data class ResolvedGeneral(
        val general: GeneralReadEntity,
        val officerLevel: Int,
        val permission: Int,
        val nationId: Int,
        val nationLevel: Int,
    ) {
        /** secret/admin feature gate input for MainControlBar (showSecret). */
        val showSecret: Boolean get() = permission >= 2
    }

    /** Resolve the caller's owned general, or null when they have no character. */
    fun resolve(userId: Long): ResolvedGeneral? {
        val general = resolveGeneral(userId) ?: return null
        val nationLevel = if (general.nationId != 0) {
            nations.findById(general.nationId).map { it.level }.orElse(0)
        } else {
            0
        }
        return ResolvedGeneral(
            general = general,
            officerLevel = general.officerLevel,
            permission = derivePermission(general.officerLevel),
            nationId = general.nationId,
            nationLevel = nationLevel,
        )
    }

    fun resolveGeneralId(userId: Long): Int? =
        resolveGeneral(userId)?.id

    private fun resolveGeneral(userId: Long): GeneralReadEntity? {
        val owner = owners.findByUserId(userId)
            ?: return generals.findByUserId(userId.toString())
        val general = generals.findById(owner.generalId.toInt()).orElse(null)
        return general?.takeIf { owner.isFinalizedFor(it, userId) }
    }

    companion object {
        /**
         * Legacy `getNationPermission` (devsam-core): officer_level 0/1 = 일반(0), 2..4 = 관직자(1),
         * 5+ = 수뇌(2). MainControlBar `permission >= 2` (기밀실) and `showSecret` both key on this.
         */
        fun derivePermission(officerLevel: Int): Int = when {
            officerLevel >= 5 -> 2
            officerLevel >= 2 -> 1
            else -> 0
        }
    }
}
