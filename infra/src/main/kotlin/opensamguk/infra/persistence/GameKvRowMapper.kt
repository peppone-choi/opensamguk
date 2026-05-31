package opensamguk.infra.persistence

import java.sql.ResultSet

/**
 * One `game_kv` row (V7 string-namespace KV store — the PHP `storage` shape generalized with a
 * `table` discriminator). Backs the string-namespace KV families the V3 int-namespace `nation_env`
 * cannot hold: `game_env` (obfuscatedNamePool, last_betting_id, last천도Trial), `betting`
 * (id_{id}), `inheritance_{id}` (the per-user survivor currency).
 *
 * `valueJson` is the byte-faithful jsonb string (insertion-order preserved). Delete-on-null
 * (KVStorage.php) is enforced by the flush executor: a null value DELETEs the `(table,namespace,key)`
 * row; a non-null value UPSERTs.
 */
data class GameKvRow(
    val table: String,
    val namespace: String,
    val key: String,
    val valueJson: String?,
)

object GameKvRowMapper {

    fun fromRow(row: Map<String, Any?>): GameKvRow = GameKvRow(
        table = stringOf(row["table"]) ?: error("game_kv.table null"),
        namespace = stringOf(row["namespace"]) ?: error("game_kv.namespace null"),
        key = stringOf(row["key"]) ?: error("game_kv.key null"),
        valueJson = stringOf(row["value"]),
    )

    fun fromResultSet(rs: ResultSet): GameKvRow = GameKvRow(
        table = rs.getString("table"),
        namespace = rs.getString("namespace"),
        key = rs.getString("key"),
        valueJson = rs.getString("value"),
    )

    fun toColumns(kv: GameKvRow): Map<String, Any?> = linkedMapOf(
        "table" to kv.table,
        "namespace" to kv.namespace,
        "key" to kv.key,
        "value" to kv.valueJson,
    )
}
