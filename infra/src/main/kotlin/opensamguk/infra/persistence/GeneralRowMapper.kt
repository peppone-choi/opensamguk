package opensamguk.infra.persistence

import opensamguk.logic.domain.General
import opensamguk.logic.util.phpRound
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
 * exp/ded float -> int (the ONLY flush-time rounding): logic `experience`/`dedication`
 * are `Double` raw accumulators (PHP `increaseVar` adds the float delta raw, no per-add round).
 * At persist, PHP binds the raw float into the `integer` column — Postgres ROUNDS the float to
 * the integer (it does NOT truncate). The G1/G2 golden proves this: e.g. 3030 + 44*0.7 = 3060.8
 * is stored as 3061, and 3030 + 64*0.7 = 3074.8 as 3075 (truncate would give 3060/3074 — wrong).
 * So this mapper ROUNDS (half-away-from-zero, [phpRound]) to the integer columns at flush. This is
 * the single place float -> int happens. (The action RNG/log byte oracle is G2.)
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
     * are ROUNDED (half-away-from-zero) to ints — the float -> integer-column store Postgres
     * performs; `meta` is rendered to a PHP-faithful jsonb string.
     */
    fun toColumns(g: General): Map<String, Any?> = linkedMapOf(
        "id" to g.id,
        "nation_id" to g.nationId,
        "city_id" to g.cityId,
        "leadership" to g.leadership,
        "strength" to g.strength,
        "intel" to g.intel,
        "injury" to g.injury,
        "experience" to phpRound(g.experience),
        "dedication" to phpRound(g.dedication),
        "officer_level" to g.officerLevel,
        "gold" to g.gold,
        "rice" to g.rice,
        "meta" to MetaJson.encode(g.meta),
    )
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
