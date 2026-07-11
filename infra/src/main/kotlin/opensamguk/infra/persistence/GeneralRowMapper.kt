package opensamguk.infra.persistence

import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.util.phpRound
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Logic `General` <-> DB `general` row mapper.
 *
 * Scalar columns: `id, nation_id, city_id, leadership, strength, intel, injury, experience,
 * dedication, officer_level, gold, rice` + the P2 military/equip surface (Task FD1):
 * `crew, crew_type_id, train, atmos, troop_id, weapon_code, book_code, horse_code, item_code,
 * npc_state` + the `last_turn` jsonb + `meta` jsonb.
 *
 * The `meta` jsonb is parsed into a `LinkedHashMap` (insertion order preserved) and
 * re-encoded with [MetaJson] (insertion-order, PHP-faithful — NOT a sorted writer).
 * `leadership_exp`/`strength_exp`/`intel_exp`/`explevel`/`dedlevel`/`aux` ride `meta` (NO dedicated
 * columns — verified against `V1__baseline.sql` AND the PHP grand-truth `hwe/sql/schema.sql:22/24/26`
 * which keeps *_exp as INT *inside the general row* but the V1/core2026 baseline moves them onto the
 * `meta` jsonb, consistent with P1 `intel_exp`/`explevel`). The row→entity path reads them back from
 * `meta`; [GeneralMeta] accessors expose them to the logic layer.
 *
 * `last_turn` (jsonb) ↔ [General.lastTurn] via [LastTurn.fromRaw]/[LastTurn.toRaw] — delete-on-default
 * jsonb, byte-comparable through [MetaJson].
 *
 * Float → int at flush (Postgres ROUNDS, half-away-from-zero, [phpRound]):
 *  - `experience`/`dedication` are `Double` raw accumulators (PHP `increaseVar` adds the float raw).
 *    The G1/G2 golden proves the ROUND: 3030 + 44*0.7 = 3060.8 → 3061, 3074.8 → 3075.
 *  - `train`/`atmos` are likewise `Double` in the logic entity but INTEGER columns in BOTH the V1
 *    baseline AND the PHP grand truth (`schema.sql:42/43` `train INT(3)`/`atmos INT(3)`). The plan's
 *    "train/atmos as float columns" diverges from the byte oracle; PHP wins — bound float → int column
 *    rounds the same way exp/ded does. This is the single place float → int happens.
 */
object GeneralRowMapper {

    /** Map a JDBC column map (e.g. from `queryForMap`) to a logic [General]. */
    fun fromRow(row: Map<String, Any?>): General = General(
        id = intOf(row["id"]),
        userId = stringOf(row["user_id"]),
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
        crew = intOf(row["crew"]),
        train = doubleOf(row["train"]),
        atmos = doubleOf(row["atmos"]),
        crewTypeId = intOf(row["crew_type_id"]),
        troop = intOf(row["troop_id"]),
        horse = codeOf(row["horse_code"]),
        weapon = codeOf(row["weapon_code"]),
        book = codeOf(row["book_code"]),
        item = codeOf(row["item_code"]),
        npcType = intOf(row["npc_state"]),
        officerCity = intOf(row["officer_city"]),
        lastTurn = LastTurn.fromRaw(MetaJson.decode(stringOf(row["last_turn"]))),
        penalty = MetaJson.decode(stringOf(row["penalty"])),
        meta = MetaJson.decode(stringOf(row["meta"])),
        // RTK14 분기 스탯(정치/매력) — devsam 패러티 외, 컬럼 부재 시 기본 50.
        politics = if (row.containsKey("politics")) intOf(row["politics"]) else 50,
        charm = if (row.containsKey("charm")) intOf(row["charm"]) else 50,
        age = row["age"]?.let(::intOf),
        turnTime = nullableInstantOf(row["turn_time"]),
    )

    /** Map a [ResultSet] (current row) to a logic [General]. */
    fun fromResultSet(rs: ResultSet): General = General(
        id = rs.getInt("id"),
        userId = rs.getString("user_id"),
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
        crew = rs.getInt("crew"),
        train = rs.getInt("train").toDouble(),
        atmos = rs.getInt("atmos").toDouble(),
        crewTypeId = rs.getInt("crew_type_id"),
        troop = rs.getInt("troop_id"),
        horse = rs.getString("horse_code") ?: "None",
        weapon = rs.getString("weapon_code") ?: "None",
        book = rs.getString("book_code") ?: "None",
        item = rs.getString("item_code") ?: "None",
        npcType = rs.getInt("npc_state"),
        officerCity = rs.getInt("officer_city"),
        lastTurn = LastTurn.fromRaw(MetaJson.decode(rs.getString("last_turn"))),
        penalty = MetaJson.decode(rs.getString("penalty")),
        meta = MetaJson.decode(rs.getString("meta")),
        // RTK14 분기 스탯(정치/매력) — V16 이후 NOT NULL DEFAULT 50 컬럼.
        politics = rs.getInt("politics"),
        charm = rs.getInt("charm"),
        age = rs.getInt("age"),
        turnTime = rs.getTimestamp("turn_time")?.toInstant(),
    )

    /**
     * Map a logic [General] back to a column map ready for binding. `experience`/`dedication`/
     * `train`/`atmos` are ROUNDED (half-away-from-zero) to ints — the float → integer-column store
     * Postgres performs. `last_turn` and `meta` are rendered to PHP-faithful jsonb strings.
     */
    fun toColumns(g: General): Map<String, Any?> = linkedMapOf(
        "id" to g.id,
        "user_id" to g.userId,
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
        "crew" to g.crew,
        "train" to phpRound(g.train),
        "atmos" to phpRound(g.atmos),
        "crew_type_id" to g.crewTypeId,
        "troop_id" to g.troop,
        "horse_code" to g.horse,
        "weapon_code" to g.weapon,
        "book_code" to g.book,
        "item_code" to g.item,
        "npc_state" to g.npcType,
        "officer_city" to g.officerCity,
        "last_turn" to MetaJson.encode(g.lastTurn.toRaw()),
        "penalty" to MetaJson.encode(g.penalty),
        "meta" to MetaJson.encode(g.meta),
        // RTK14 분기 스탯(정치/매력) — 컬럼 맵 끝에 APPEND(기존 컬럼 순서 불변).
        "politics" to g.politics,
        "charm" to g.charm,
        "age" to g.age,
        "turn_time" to g.turnTime?.toString(),
    )
}

/** Equip-slot code: a null DB value materializes as the `None` sentinel (V1 `*_code` default). */
internal fun codeOf(v: Any?): String = when (v) {
    null -> "None"
    is String -> v
    else -> v.toString()
}

internal fun intOf(v: Any?): Int = when (v) {
    null -> 0
    is Int -> v
    is Number -> v.toInt()
    is String -> v.toInt()
    else -> error("not an int: $v")
}

/** Like [intOf] but a null column value stays null (for nullable columns, e.g. `city.trade`). */
internal fun nullableIntOf(v: Any?): Int? = when (v) {
    null -> null
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

internal fun nullableInstantOf(v: Any?): Instant? = when (v) {
    null -> null
    is Instant -> v
    is Timestamp -> v.toInstant()
    is OffsetDateTime -> v.toInstant()
    is String -> runCatching { Instant.parse(v) }.getOrElse { OffsetDateTime.parse(v).toInstant() }
    else -> error("not an instant: $v")
}
