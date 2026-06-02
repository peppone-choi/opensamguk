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

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
