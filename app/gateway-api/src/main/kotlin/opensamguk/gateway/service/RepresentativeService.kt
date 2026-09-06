package opensamguk.gateway.service

import opensamguk.gateway.dto.RepresentativeCandidate
import opensamguk.gateway.dto.RepresentativeResponse
import opensamguk.gateway.dto.RepresentativeState
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.infra.read.OwnedGeneralReader
import opensamguk.infra.read.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * ADR-LITE-049 13 — 계정 대표 장수(커뮤니티 글·댓글의 서버 배지·아이콘 원천).
 * 소유 검증은 `general.user_id = 계정 id` 한 축뿐이다 — 남의 장수를 대표로 세울 수 없다.
 */
@Service
class RepresentativeService(
    private val userRepository: UserRepository,
    private val ownedGeneralReader: OwnedGeneralReader,
) {
    @Transactional(readOnly = true)
    fun current(userDetails: CustomUserDetails): RepresentativeResponse {
        val user = userRepository.findByUsername(userDetails.username)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다.") }
        return RepresentativeResponse(
            current = RepresentativeState(user.representativeGeneralId, user.representativeGeneralName, user.representativeWorldId),
            candidates = ownedGeneralReader.findByUserId(user.id).map {
                RepresentativeCandidate(generalId = it.id, name = it.name, worldId = it.worldId, scenarioCode = it.scenarioCode)
            },
        )
    }

    @Transactional
    fun set(userDetails: CustomUserDetails, generalId: Int?): RepresentativeResponse {
        val user = userRepository.findByUsernameForUpdate(userDetails.username)
            .orElseThrow { IllegalArgumentException("사용자를 찾을 수 없습니다.") }
        val owned = ownedGeneralReader.findByUserId(user.id)
        if (generalId == null) {
            user.representativeGeneralId = null
            user.representativeGeneralName = null
            user.representativeWorldId = null
        } else {
            val target = owned.firstOrNull { it.id == generalId }
                ?: throw IllegalArgumentException("내 장수만 대표 장수로 정할 수 있습니다.")
            user.representativeGeneralId = target.id
            user.representativeGeneralName = target.name
            user.representativeWorldId = target.worldId
        }
        user.updatedAt = java.time.LocalDateTime.now()
        userRepository.saveAndFlush(user)
        return RepresentativeResponse(
            current = RepresentativeState(user.representativeGeneralId, user.representativeGeneralName, user.representativeWorldId),
            candidates = owned.map { RepresentativeCandidate(it.id, it.name, it.worldId, it.scenarioCode) },
        )
    }
}
