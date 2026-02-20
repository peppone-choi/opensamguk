package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "bet_entry")
class BetEntry(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "betting_id", nullable = false)
    var bettingId: Long = 0,

    @Column(name = "general_id", nullable = false)
    var generalId: Long = 0,

    @Column(nullable = false)
    var choice: String = "",

    @Column(nullable = false)
    var amount: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
