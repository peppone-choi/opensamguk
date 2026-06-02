package opensamguk.infra.persistence

import java.sql.ResultSet

/**
 * One `ng_betting` row (PHP `ng_betting`, schema.sql:644). The `bettingType` is a JSON int-array key
 * (`purifyBettingKey`/`convertBettingKey` → `[1,2]` no-space) kept as the raw byte-faithful string —
 * the unique index `(general_id, betting_id, betting_type)` + the `amount +=` UPSERT semantics
 * (bet() re-bet, Betting.php:100-183) are enforced at the flush channel (T-Betting). `userId` is
 * nullable (system/NPC bets carry no user).
 */
data class NgBettingRow(
    val id: Int?,
    val bettingId: Int,
    val generalId: Int,
    val userId: Int?,
    val bettingType: String,
    val amount: Int,
)

object NgBettingRowMapper {

    fun fromRow(row: Map<String, Any?>): NgBettingRow = NgBettingRow(
        id = nullableIntOf(row["id"]),
        bettingId = intOf(row["betting_id"]),
        generalId = intOf(row["general_id"]),
        userId = nullableIntOf(row["user_id"]),
        bettingType = stringOf(row["betting_type"]) ?: error("ng_betting.betting_type null"),
        amount = intOf(row["amount"]),
    )

    fun fromResultSet(rs: ResultSet): NgBettingRow {
        val userId = rs.getInt("user_id").let { if (rs.wasNull()) null else it }
        return NgBettingRow(
            id = rs.getInt("id"),
            bettingId = rs.getInt("betting_id"),
            generalId = rs.getInt("general_id"),
            userId = userId,
            bettingType = rs.getString("betting_type"),
            amount = rs.getInt("amount"),
        )
    }

    fun toColumns(b: NgBettingRow): Map<String, Any?> = linkedMapOf(
        "betting_id" to b.bettingId,
        "general_id" to b.generalId,
        "user_id" to b.userId,
        "betting_type" to b.bettingType,
        "amount" to b.amount,
    )
}
