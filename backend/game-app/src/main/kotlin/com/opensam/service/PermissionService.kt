package com.opensam.service

import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Manages ambassador/auditor permission assignments for a nation.
 * Legacy: j_general_set_permission.php
 */
@Service
class PermissionService(
    private val generalRepository: GeneralRepository,
) {
    data class PermissionResult(val result: Boolean, val reason: String)

    /**
     * Sets ambassador or auditor permissions for specified generals.
     * Only the nation leader (officer_level=12) can do this.
     *
     * @param requesterId the general making the request
     * @param isAmbassador true for ambassador (외교권자), false for auditor (감찰관)
     * @param generalIds list of general IDs to assign the permission
     */
    @Transactional
    fun setPermission(requesterId: Long, isAmbassador: Boolean, generalIds: List<Long>): PermissionResult {
        val requester = generalRepository.findById(requesterId).orElse(null)
            ?: return PermissionResult(false, "장수를 찾을 수 없습니다.")

        if (requester.officerLevel.toInt() != 12) {
            return PermissionResult(false, "군주가 아닙니다")
        }

        val nationId = requester.nationId

        val targetType = if (isAmbassador) "ambassador" else "auditor"
        val maxCount = if (isAmbassador) 2 else Int.MAX_VALUE

        if (generalIds.size > maxCount) {
            return PermissionResult(false, "외교권자는 최대 둘까지만 설정 가능합니다.")
        }

        // Clear existing permissions of this type in the nation
        val nationGenerals = generalRepository.findByNationId(nationId)
        nationGenerals.filter { it.permission == targetType }.forEach { g ->
            g.permission = "normal"
            generalRepository.save(g)
        }

        // Set new permissions
        for (gId in generalIds) {
            val general = generalRepository.findById(gId).orElse(null) ?: continue
            if (general.nationId != nationId) continue
            general.permission = targetType
            generalRepository.save(general)
        }

        return PermissionResult(true, "success")
    }
}
