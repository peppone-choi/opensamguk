package opensamguk.infra.persistence

import opensamguk.logic.domain.General
import java.sql.ResultSet

/**
 * Logic `General` <-> DB `general` row mapper.
 *
 * Columns (Task D1): `id, nation_id, city_id, leadership, strength, intel, injury,
 * experience, dedication, officer_level, gold, rice, meta`.
 *
 * The `meta` jsonb is parsed into a `LinkedHashMap` (insertion order preserved) and
 * re-encoded with [MetaJson] (insertion-order, PHP-faithful — NOT a sorted writer).
 *
 * exp/ded truncation (the ONLY flush-time rounding): logic `experience`/`dedication`
 * are `Double` raw accumulators; this mapper truncates them TOWARD ZERO to the integer
 * columns at flush (`truncate(...).toInt()`). This is the single place float -> int happens.
 */
object GeneralRowMapper {

    /** Map a JDBC column map (e.g. from `queryForMap`) to a logic [General]. */
    fun fromRow(row: Map<String, Any?>): General = General(
        id = intOf(row["id"]),
        nationId = intOf(row["nation_id"]),
        cityId = intOf(row["city_id"]),
        leadership = intOf(row["leadership"]),
        strength = intOf(row["strength"]),
        intel = intOf(row["intel"]),
        injury = intOf(row["injury"]),
        experience = doubleOf(row["experience"]),
        dedication = doubleOf(row["dedication"]),
        officerLevel = intOf(row["officer_level"]),
        gold = intOf(row["gold"]),
        rice = intOf(row["rice"]),
        meta = MetaJson.decode(stringOf(row["meta"])),
    )

    /** Map a [ResultSet] (current row) to a logic [General]. */
    fun fromResultSet(rs: ResultSet): General = General(
        id = rs.getInt("id"),
        nationId = rs.getInt("nation_id"),
        cityId = rs.getInt("city_id"),
        leadership = rs.getInt("leadership"),
        strength = rs.getInt("strength"),
        intel = rs.getInt("intel"),
        injury = rs.getInt("injury"),
        experience = rs.getInt("experience").toDouble(),
        dedication = rs.getInt("dedication").toDouble(),
        officerLevel = rs.getInt("officer_level"),
        gold = rs.getInt("gold"),
        rice = rs.getInt("rice"),
        meta = MetaJson.decode(rs.getString("meta")),
    )

    /**
     * Map a logic [General] back to a column map ready for binding. `experience`/`dedication`
     * are truncated toward zero to ints; `meta` is rendered to a PHP-faithful jsonb string.
     */
    fun toColumns(g: General): Map<String, Any?> = linkedMapOf(
        "id" to g.id,
        "nation_id" to g.nationId,
        "city_id" to g.cityId,
        "leadership" to g.leadership,
        "strength" to g.strength,
        "intel" to g.intel,
        "injury" to g.injury,
        "experience" to truncToInt(g.experience),
        "dedication" to truncToInt(g.dedication),
        "officer_level" to g.officerLevel,
        "gold" to g.gold,
        "rice" to g.rice,
        "meta" to MetaJson.encode(g.meta),
    )

    /** Truncate toward zero (PHP int-cast of a float var at storage). */
    private fun truncToInt(d: Double): Int = kotlin.math.truncate(d).toInt()
}

internal fun intOf(v: Any?): Int = when (v) {
    null -> 0
    is Int -> v
    is Number -> v.toInt()
    is String -> v.toInt()
    else -> error("not an int: $v")
}

internal fun doubleOf(v: Any?): Double = when (v) {
    null -> 0.0
    is Double -> v
    is Number -> v.toDouble()
    is String -> v.toDouble()
    else -> error("not a double: $v")
}

internal fun stringOf(v: Any?): String? = when (v) {
    null -> null
    is String -> v
    else -> v.toString()
}
