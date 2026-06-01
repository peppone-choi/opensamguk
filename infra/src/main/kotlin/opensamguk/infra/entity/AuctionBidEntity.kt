package opensamguk.infra.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * JPA entity for `ng_auction_bid` table (PHP `ng_auction_bid`, schema.sql:709).
 *
 * `no` is the SERIAL primary key (PHP uses `no`, not `id`). `owner` is nullable.
 * `aux` is jsonb — stored as raw string.
 */
@Entity
@Table(name = "ng_auction_bid")
class AuctionBidEntity(
    @Column(name = "auction_id", nullable = false)
    var auctionId: Int,

    @Column(name = "owner")
    var owner: Int? = null,

    @Column(name = "general_id", nullable = false)
    var generalId: Int,

    @Column(name = "amount", nullable = false)
    var amount: Int,

    @Column(name = "date", nullable = false)
    var date: Instant,

    @Column(name = "aux", nullable = false, columnDefinition = "jsonb")
    var aux: String = "{}",

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "no")
    var no: Int? = null,
)
