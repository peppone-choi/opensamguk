package opensamguk.infra.persistence

import java.sql.ResultSet
import java.time.Instant

/**
 * One `ng_auction_bid` row (PHP `ng_auction_bid`, schema.sql:709). Outbid rows are NEVER deleted
 * (research §3: ng_auction_bid has NO delete on outbid — the refund is a General-resource credit +
 * Message, not a tombstone) — the flush channel (T0.7) is INSERT-only for this table. `owner` is
 * nullable; `auxJson` carries the byte-faithful bid aux (AuctionBidItemData codec is T0.7).
 *
 * `date` is wall-clock (QUARANTINED — research §11); carried but not byte-asserted on the instant.
 */
data class AuctionBidRow(
    val no: Int?,
    val auctionId: Int,
    val owner: Int?,
    val generalId: Int,
    val amount: Int,
    val date: Instant,
    val auxJson: String,
)

object AuctionBidRowMapper {

    fun fromRow(row: Map<String, Any?>): AuctionBidRow = AuctionBidRow(
        no = nullableIntOf(row["no"]),
        auctionId = intOf(row["auction_id"]),
        owner = nullableIntOf(row["owner"]),
        generalId = intOf(row["general_id"]),
        amount = intOf(row["amount"]),
        date = instantOf(row["date"]),
        auxJson = stringOf(row["aux"]) ?: "{}",
    )

    fun fromResultSet(rs: ResultSet): AuctionBidRow {
        val owner = rs.getInt("owner").let { if (rs.wasNull()) null else it }
        return AuctionBidRow(
            no = rs.getInt("no"),
            auctionId = rs.getInt("auction_id"),
            owner = owner,
            generalId = rs.getInt("general_id"),
            amount = rs.getInt("amount"),
            date = rs.getTimestamp("date").toInstant(),
            auxJson = rs.getString("aux") ?: "{}",
        )
    }

    /** Column map for INSERT (no omitted — assigned by SERIAL; outbid rows persist, never deleted). */
    fun toColumns(b: AuctionBidRow): Map<String, Any?> = linkedMapOf(
        "auction_id" to b.auctionId,
        "owner" to b.owner,
        "general_id" to b.generalId,
        "amount" to b.amount,
        "date" to b.date,
        "aux" to b.auxJson,
    )
}
