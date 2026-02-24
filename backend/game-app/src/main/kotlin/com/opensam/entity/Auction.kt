package com.opensam.entity

import jakarta.persistence.*
import java.time.OffsetDateTime

@Entity
@Table(name = "auction")
class Auction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @Column(name = "world_id", nullable = false)
    var worldId: Long = 0,

    @Column(name = "seller_general_id", nullable = false)
    var sellerGeneralId: Long = 0,

    @Column(name = "item_code", nullable = false)
    var itemCode: String = "",

    @Column(name = "min_price", nullable = false)
    var minPrice: Int = 0,

    @Column(name = "current_price", nullable = false)
    var currentPrice: Int = 0,

    @Column(name = "buyer_general_id")
    var buyerGeneralId: Long? = null,

    @Column(nullable = false)
    var status: String = "open",

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "expires_at", nullable = false)
    var expiresAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(columnDefinition = "jsonb")
    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    var meta: MutableMap<String, Any> = mutableMapOf(),
)
