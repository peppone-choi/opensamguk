package opensamguk.infra.persistence

import java.sql.ResultSet
import java.time.Instant

/**
 * One `ng_auction` row (PHP `ng_auction`, schema.sql:690 — reconciled in V7 from the V1 TS-shaped
 * placeholder). `type` ∈ {buyRice,sellRice,uniqueItem}; `reqResource` ∈ {gold,rice,inheritPoint};
 * `finished` is a boolean (PHP BIT(1)); `detailJson` is the byte-faithful detail jsonb (NullIsUndefined
 * omit-null + JsonString nested-as-string — the logic AuctionInfo/AuctionInfoDetail codec is T0.7).
 *
 * `openDate`/`closeDate` are wall-clock (QUARANTINED — research §11): the flush carries them but the
 * parity gate asserts the relative interval (`open + max(MIN, turnterm*COEFF)*60s`), modeled as a
 * game-tick predicate `game-time >= close_date`, never the absolute instant.
 */
data class AuctionRow(
    val id: Int?,
    val type: String,
    val finished: Boolean,
    val target: String?,
    val hostGeneralId: Int,
    val reqResource: String,
    val openDate: Instant,
    val closeDate: Instant,
    val detailJson: String,
)

object AuctionRowMapper {

    fun fromRow(row: Map<String, Any?>): AuctionRow = AuctionRow(
        id = nullableIntOf(row["id"]),
        type = stringOf(row["type"]) ?: error("ng_auction.type null"),
        finished = boolOf(row["finished"]),
        target = stringOf(row["target"]),
        hostGeneralId = intOf(row["host_general_id"]),
        reqResource = stringOf(row["req_resource"]) ?: error("ng_auction.req_resource null"),
        openDate = instantOf(row["open_date"]),
        closeDate = instantOf(row["close_date"]),
        detailJson = stringOf(row["detail"]) ?: "{}",
    )

    fun fromResultSet(rs: ResultSet): AuctionRow = AuctionRow(
        id = rs.getInt("id"),
        type = rs.getString("type"),
        finished = rs.getBoolean("finished"),
        target = rs.getString("target"),
        hostGeneralId = rs.getInt("host_general_id"),
        reqResource = rs.getString("req_resource"),
        openDate = rs.getTimestamp("open_date").toInstant(),
        closeDate = rs.getTimestamp("close_date").toInstant(),
        detailJson = rs.getString("detail") ?: "{}",
    )

    /** Column map for UPSERT (`type`/`req_resource` bind through `CAST(... AS ng_auction_*)`). */
    fun toColumns(a: AuctionRow): Map<String, Any?> = linkedMapOf(
        "type" to a.type,
        "finished" to a.finished,
        "target" to a.target,
        "host_general_id" to a.hostGeneralId,
        "req_resource" to a.reqResource,
        "open_date" to a.openDate,
        "close_date" to a.closeDate,
        "detail" to a.detailJson,
    )
}

internal fun boolOf(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Number -> v.toInt() != 0
    is String -> v == "t" || v == "true" || v == "1"
    else -> error("not a boolean: $v")
}
