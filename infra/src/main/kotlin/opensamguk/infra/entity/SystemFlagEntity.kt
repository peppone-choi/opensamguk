package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 전역 시스템 플래그 — legacy `system` 테이블(REG/LOGIN/NOTICE) 대응.
 *
 * legacy는 `system` 행을 `WHERE NO = 1`로 단일 조회한다(REG/LOGIN = 'Y'/'N' VARCHAR(1)).
 * opensamguk은 boolean으로 정규화하고 id=1 단일 행만 사용한다([SystemFlagRepository.SINGLETON_ID]).
 *
 * AuthService의 가입/로그인 경로가 이 플래그를 게이트로 참조해야 한다(B2b).
 */
@Entity
@Table(name = "system_flag")
class SystemFlagEntity(
    @Id
    @Column(name = "id")
    var id: Long = 1,

    // legacy system.REG = 'Y' — 전역 가입 허용.
    @Column(name = "allow_join", nullable = false)
    var allowJoin: Boolean = false,

    // legacy system.LOGIN = 'Y' — 전역 로그인 허용.
    @Column(name = "allow_login", nullable = false)
    var allowLogin: Boolean = false,

    // legacy system.NOTICE — 공지.
    @Column(name = "notice", nullable = false, length = 256)
    var notice: String = "",

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
