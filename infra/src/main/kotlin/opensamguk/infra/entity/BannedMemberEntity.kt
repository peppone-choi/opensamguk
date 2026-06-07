package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 영구 이메일 차단 — legacy `banned_member` 테이블 대응.
 *
 * grand truth: legacy BanEmailAddress.php:46
 *   `hashed_email => hash('sha512', globalSalt . email . globalSalt)`
 * → opensamguk은 [opensamguk.infra.read.BannedMemberRepository.hashEmail]로 동일 해시를 산출한다.
 *
 * 회원가입 경로가 입력 이메일의 해시 존재 여부를 검사해 차단해야 한다(B2e).
 */
@Entity
@Table(name = "banned_member")
class BannedMemberEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,

    // SHA512(globalSalt | email | globalSalt) hex — 평문 이메일은 저장하지 않는다.
    @Column(name = "hashed_email", nullable = false, unique = true, length = 128)
    val hashedEmail: String,

    // 부가정보(등록 시각 등 — legacy는 TimeUtil::now() 문자열).
    @Column(name = "info", nullable = true)
    val info: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
