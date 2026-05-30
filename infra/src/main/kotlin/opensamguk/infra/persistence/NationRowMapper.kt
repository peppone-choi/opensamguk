package opensamguk.infra.persistence

import opensamguk.logic.domain.Nation
import java.sql.ResultSet

/**
 * Logic `Nation` <-> DB `nation` row mapper.
 *
 * Columns (V1 baseline `nation`): `id, name, color, capital_city_id, gold, rice, tech, level,
 * type_code, meta`. `tech` is `double precision` (the only float scalar column here).
 *
 * `gennum`/`capset` have NO dedicated columns — they RIDE the `meta` jsonb (FD0 LogicEntities note:
 * "rate/bill/surlimit/secretlimit/strategicCmdLimit/aux + gennum/capset source"). The row mapper reads
 * them back from `meta` on materialize; on write, `meta` itself is the byte-comparable source of truth
 * (re-encoded with [MetaJson], insertion order preserved against the GATE-SATELLITE golden), so the
 * mapper writes `meta` AS-IS rather than re-injecting the convenience fields and disturbing key order.
 */
object NationRowMapper {

    /** Map a JDBC column map to a logic [Nation]. */
    fun fromRow(row: Map<String, Any?>): Nation {
        val meta = MetaJson.decode(stringOf(row["meta"]))
        return Nation(
            id = intOf(row["id"]),
            level = intOf(row["level"]),
            capitalCityId = nullableIntOf(row["capital_city_id"]),
            name = stringOf(row["name"]) ?: "",
            color = stringOf(row["color"]) ?: "",
            typeCode = stringOf(row["type_code"]) ?: "che_중립",
            gold = intOf(row["gold"]),
            rice = intOf(row["rice"]),
            tech = doubleOf(row["tech"]),
            gennum = intOf(meta["gennum"]),
            capset = intOf(meta["capset"]),
            meta = meta,
        )
    }

    /** Map a [ResultSet] (current row) to a logic [Nation]. */
    fun fromResultSet(rs: ResultSet): Nation {
        val meta = MetaJson.decode(rs.getString("meta"))
        return Nation(
            id = rs.getInt("id"),
            level = rs.getInt("level"),
            capitalCityId = rs.getInt("capital_city_id").let { if (rs.wasNull()) null else it },
            name = rs.getString("name") ?: "",
            color = rs.getString("color") ?: "",
            typeCode = rs.getString("type_code") ?: "che_중립",
            gold = rs.getInt("gold"),
            rice = rs.getInt("rice"),
            tech = rs.getDouble("tech"),
            gennum = intOf(meta["gennum"]),
            capset = intOf(meta["capset"]),
            meta = meta,
        )
    }

    /** Map a logic [Nation] back to a column map ready for binding. `meta` is the jsonb source. */
    fun toColumns(n: Nation): Map<String, Any?> = linkedMapOf(
        "id" to n.id,
        "name" to n.name,
        "color" to n.color,
        "capital_city_id" to n.capitalCityId,
        "gold" to n.gold,
        "rice" to n.rice,
        "tech" to n.tech,
        "level" to n.level,
        "type_code" to n.typeCode,
        "meta" to MetaJson.encode(n.meta),
    )
}
