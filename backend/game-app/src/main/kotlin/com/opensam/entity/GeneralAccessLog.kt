package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "general_access_log")
class GeneralAccessLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "general_id", nullable = false)
    var generalId: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(name = "accessed_at", nullable = false)
    var accessedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "ip_address")
    var ipAddress: String? = null,

    @Column(nullable = false)
    var refresh: Int = 0,

    @Column(name = "refresh_score_total", nullable = false)
    var refreshScoreTotal: Int = 0,
)
