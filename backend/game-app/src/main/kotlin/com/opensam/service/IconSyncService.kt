package com.opensam.service

import com.opensam.repository.AppUserRepository
import com.opensam.repository.GeneralRepository
import org.springframework.stereotype.Service

/**
 * Syncs the general's icon/picture from the member (account) profile.
 * Legacy: j_adjust_icon.php
 */
@Service
class IconSyncService(
    private val generalRepository: GeneralRepository,
    private val appUserRepository: AppUserRepository,
) {
    data class SyncResult(val result: Boolean, val reason: String)

    fun syncIcon(loginId: String): SyncResult {
        val user = appUserRepository.findByLoginId(loginId)
            ?: return SyncResult(false, "회원 기록 정보가 없습니다")

        val generals = generalRepository.findByUserIdAndNpcState(user.id, 0)
        if (generals.isEmpty()) {
            return SyncResult(true, "등록된 장수가 없습니다")
        }

        for (general in generals) {
            general.picture = user.picture ?: general.picture
            general.imageServer = user.imageServer ?: general.imageServer
            generalRepository.save(general)
        }

        return SyncResult(true, "success")
    }
}
