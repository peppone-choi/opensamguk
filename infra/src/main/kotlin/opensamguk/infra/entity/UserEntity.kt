package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 사용자 엔티티 — 인증/인가 기반.
 *
 * Password는 BCrypt 인코딩된 문자열 (평문 저장 금지).
 */
@Entity
@Table(name = "users")
class UserEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true, length = 50)
    val username: String,

    @Column(nullable = false, length = 255)
    var password: String,

    @Column(nullable = true, unique = true, length = 100)
    val email: String? = null,

    @Column(nullable = true, length = 50)
    val nickname: String? = null,

    @Column(nullable = false, length = 20)
    val role: String = "USER",

    // ── 회원관리(어드민) 컬럼 — legacy `member` 대응 (V11) ──
    // legacy member.GRADE(0–9 다단계). 0.9.0은 role 단일 게이트 유지 → nullable(미사용 기본 null).
    // 1.0.0 멀티운영자(B-AUTH-EXT) 대비로 컬럼만 보유한다.
    @Column(name = "grade", nullable = true)
    var grade: Int? = null,

    // legacy member.BLOCK_DATE — 차단 만료 시각(null이면 미차단).
    @Column(name = "block_until", nullable = true)
    var blockUntil: LocalDateTime? = null,

    // legacy member.delete_after — 탈퇴 예정 시각(null이면 탈퇴 예약 없음).
    @Column(name = "delete_after", nullable = true)
    var deleteAfter: LocalDateTime? = null,

    // legacy member.oauth_type ENUM('NONE','KAKAO'). 로컬 인증은 null/NONE.
    @Column(name = "oauth_type", nullable = true, length = 16)
    var oauthType: String? = null,

    // legacy member.PICTURE — 장수 아이콘 파일명.
    @Column(name = "picture", nullable = true, length = 64)
    var picture: String? = null,

    // legacy member.IMGSVR — 이미지 서버 분기 플래그.
    @Column(name = "imgsvr", nullable = false)
    var imgsvr: Boolean = false,

    // legacy member_log(action_type=login) 최신 행 대체 — 최근 로그인 시각.
    @Column(name = "last_login_at", nullable = true)
    var lastLoginAt: LocalDateTime? = null,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
