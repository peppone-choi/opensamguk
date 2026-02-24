package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "traffic_snapshot")
class TrafficSnapshot(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(nullable = false)
    var year: Short = 0,

    @Column(nullable = false)
    var month: Short = 0,

    @Column(nullable = false)
    var refresh: Int = 0,

    @Column(nullable = false)
    var online: Int = 0,

    @Column(name = "recorded_at", nullable = false)
    var recordedAt: OffsetDateTime = OffsetDateTime.now(),
)
