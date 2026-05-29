package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import java.sql.ResultSet

/**
 * Logic `City` <-> DB `city` row mapper.
 *
 * Columns (Task D1): `id, nation_id, level, comm, comm_max, agri, agri_max,
 * supply_state, front_state, trust, meta`.
 *
 * `trust` is a logic `Double` (che math uses `trust/100.0` & `trust/80.0`); the V1 baseline
 * `city.trust` column is INTEGER and the G1 golden pins an integer-valued trust so the column
 * is lossless. The mapper reads int -> Double and writes Double -> int (truncate toward zero).
 *
 * `meta` jsonb: decoded into an insertion-order `LinkedHashMap`, re-encoded with [MetaJson].
 */
object CityRowMapper {

    /** Map a JDBC column map to a logic [City]. */
    fun fromRow(row: Map<String, Any?>): City = City(
        id = intOf(row["id"]),
        nationId = intOf(row["nation_id"]),
        level = intOf(row["level"]),
        commerce = intOf(row["comm"]),
        commerceMax = intOf(row["comm_max"]),
        agriculture = intOf(row["agri"]),
        agricultureMax = intOf(row["agri_max"]),
        supplyState = intOf(row["supply_state"]),
        frontState = intOf(row["front_state"]),
        trust = doubleOf(row["trust"]),
        meta = MetaJson.decode(stringOf(row["meta"])),
    )

    /** Map a [ResultSet] (current row) to a logic [City]. */
    fun fromResultSet(rs: ResultSet): City = City(
        id = rs.getInt("id"),
        nationId = rs.getInt("nation_id"),
        level = rs.getInt("level"),
        commerce = rs.getInt("comm"),
        commerceMax = rs.getInt("comm_max"),
        agriculture = rs.getInt("agri"),
        agricultureMax = rs.getInt("agri_max"),
        supplyState = rs.getInt("supply_state"),
        frontState = rs.getInt("front_state"),
        trust = rs.getInt("trust").toDouble(),
        meta = MetaJson.decode(rs.getString("meta")),
    )

    /** Map a logic [City] back to a column map ready for binding. */
    fun toColumns(c: City): Map<String, Any?> = linkedMapOf(
        "id" to c.id,
        "nation_id" to c.nationId,
        "level" to c.level,
        "comm" to c.commerce,
        "comm_max" to c.commerceMax,
        "agri" to c.agriculture,
        "agri_max" to c.agricultureMax,
        "supply_state" to c.supplyState,
        "front_state" to c.frontState,
        "trust" to kotlin.math.truncate(c.trust).toInt(),
        "meta" to MetaJson.encode(c.meta),
    )
}
