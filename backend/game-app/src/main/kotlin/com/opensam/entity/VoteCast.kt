package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(
    name = "vote_cast",
    uniqueConstraints = [UniqueConstraint(columnNames = ["vote_id", "general_id"])]
)
class VoteCast(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(name = "vote_id", nullable = false)
    var voteId: Long = 0,

    @Column(name = "general_id", nullable = false)
    var generalId: Long = 0,

    @Column(name = "option_idx", nullable = false)
    var optionIdx: Short = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
