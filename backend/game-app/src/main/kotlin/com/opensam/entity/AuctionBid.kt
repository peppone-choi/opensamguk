package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "auction_bid")
class AuctionBid(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "auction_id", nullable = false)
    var auctionId: Long = 0,

    @Column(name = "bidder_general_id", nullable = false)
    var bidderGeneralId: Long = 0,

    @Column(nullable = false)
    var amount: Int = 0,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)
