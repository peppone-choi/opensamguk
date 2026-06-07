package opensamguk.infra.read

import opensamguk.infra.entity.BannedMemberEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 영구차단 이메일 저장소 — legacy `banned_member` 대응.
 *
 * 해시는 [EmailHasher.hash]로 산출한다(legacy sha512(salt|email|salt) 패러티).
 * 회원가입 경로(B2e)는 `existsByHashedEmail(emailHasher.hash(email))`로 차단 여부를 검사한다.
 * 어드민 영구차단 등록부(B2e)는 동일 해시로 [BannedMemberEntity]를 save 한다 — 중복은 unique 제약으로 거부.
 */
@Repository
interface BannedMemberRepository : JpaRepository<BannedMemberEntity, Long> {
    fun existsByHashedEmail(hashedEmail: String): Boolean
}
