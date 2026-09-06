package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 게이트웨이 공지 — 로그인·로비 우측 「공지」(ADR-LITE-049). 계정 층(gateway-api) 소유, JPA 쓰기.
 * 게임 월드 상태가 아니므로 one-daemon-write-rule 대상이 아니다.
 */
@Entity
@Table(name = "gateway_notice")
class GatewayNoticeEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, length = 120)
    var title: String,
    /** 평문(줄바꿈 유지). HTML 아님. */
    @Column(nullable = false, columnDefinition = "TEXT")
    var body: String,
    @Column(nullable = false)
    var pinned: Boolean = false,
    @Column(name = "published_at", nullable = false)
    var publishedAt: Instant = Instant.now(),
    @Column(name = "created_by_account_id", nullable = true)
    val createdByAccountId: Long? = null,
    @Column(name = "deleted_at", nullable = true)
    var deletedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)
