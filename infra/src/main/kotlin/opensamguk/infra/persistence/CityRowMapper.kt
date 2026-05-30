package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import java.sql.ResultSet

/**
 * Logic `City` <-> DB `city` row mapper.
 *
 * Scalar columns: `id, nation_id, level, comm, comm_max, agri, agri_max, supply_state, front_state,
 * trust` + the P2 develop/defense surface (Task FD1): `secu, secu_max, def, def_max, wall, wall_max,
 * pop, pop_max, trade, region` + `meta` jsonb. (There is NO `city.tech` — tech is a NATION stat.)
 *
 * `trust` is a logic `Double` (che math uses `trust/100.0` & `trust/80.0`); the V1 baseline
 * `city.trust` column is INTEGER and the G1 golden pins an integer-valued trust so the column
 * is lossless. The mapper reads int -> Double and writes Double -> int (truncate toward zero).
 *
 * `trade` is a NULLABLE logic `Int?` (95..105 or null = disabled). The V1 baseline `city.trade`
 * column is `integer NOT NULL DEFAULT 100`; a null logic value writes the DEFAULT-equivalent — but
 * the row mapper preserves null↔value faithfully (a null binds SQL NULL; the read widens an absent
 * column to null). The golden DB seeds an explicit integer, so the round trip is lossless there.
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
        security = intOf(row["secu"]),
        securityMax = intOf(row["secu_max"]),
        defense = intOf(row["def"]),
        defenseMax = intOf(row["def_max"]),
        wall = intOf(row["wall"]),
        wallMax = intOf(row["wall_max"]),
        population = intOf(row["pop"]),
        populationMax = intOf(row["pop_max"]),
        trade = nullableIntOf(row["trade"]),
        region = intOf(row["region"]),
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
        security = rs.getInt("secu"),
        securityMax = rs.getInt("secu_max"),
        defense = rs.getInt("def"),
        defenseMax = rs.getInt("def_max"),
        wall = rs.getInt("wall"),
        wallMax = rs.getInt("wall_max"),
        population = rs.getInt("pop"),
        populationMax = rs.getInt("pop_max"),
        trade = rs.getInt("trade").let { if (rs.wasNull()) null else it },
        region = rs.getInt("region"),
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
        "secu" to c.security,
        "secu_max" to c.securityMax,
        "def" to c.defense,
        "def_max" to c.defenseMax,
        "wall" to c.wall,
        "wall_max" to c.wallMax,
        "pop" to c.population,
        "pop_max" to c.populationMax,
        "trade" to c.trade,
        "region" to c.region,
        "meta" to MetaJson.encode(c.meta),
    )
}
