package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "tournament")
class Tournament(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(name = "general_id", nullable = false)
    var generalId: Long = 0,

    @Column(nullable = false)
    var round: Short = 0,

    @Column(name = "bracket_position", nullable = false)
    var bracketPosition: Short = 0,

    @Column(name = "opponent_id")
    var opponentId: Long? = null,

    @Column(nullable = false)
    var result: Short = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
