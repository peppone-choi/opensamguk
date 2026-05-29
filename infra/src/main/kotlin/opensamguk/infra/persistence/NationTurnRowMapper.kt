package opensamguk.infra.persistence

import opensamguk.logic.domain.NationTurn
import java.sql.ResultSet

/**
 * Logic `NationTurn` <-> DB `nation_turn` row mapper.
 *
 * Columns (V1 baseline + FD0a V2): `nation_id, officer_level, turn_idx, action_code, arg (jsonb),
 * brief (text — V2 added, NOT jsonb)`. The logic [NationTurn] models `arg` as a nullable `Map?`
 * (an empty/absent jsonb materializes as null; the row mapper binds null as `{}` jsonb to match the
 * baseline `NOT NULL DEFAULT '{}'`) and `brief` as a non-null String defaulting `''`.
 */
object NationTurnRowMapper {

    /** Map a JDBC column map to a logic [NationTurn]. */
    fun fromRow(row: Map<String, Any?>): NationTurn {
        val argMap = MetaJson.decode(stringOf(row["arg"]))
        return NationTurn(
            nationId = intOf(row["nation_id"]),
            officerLevel = intOf(row["officer_level"]),
            turnIdx = intOf(row["turn_idx"]),
            action = stringOf(row["action_code"]) ?: "",
            arg = if (argMap.isEmpty()) null else argMap,
            brief = stringOf(row["brief"]) ?: "",
        )
    }

    /** Map a [ResultSet] (current row) to a logic [NationTurn]. */
    fun fromResultSet(rs: ResultSet): NationTurn {
        val argMap = MetaJson.decode(rs.getString("arg"))
        return NationTurn(
            nationId = rs.getInt("nation_id"),
            officerLevel = rs.getInt("officer_level"),
            turnIdx = rs.getInt("turn_idx"),
            action = rs.getString("action_code") ?: "",
            arg = if (argMap.isEmpty()) null else argMap,
            brief = rs.getString("brief") ?: "",
        )
    }

    /**
     * Map a logic [NationTurn] back to a column map ready for binding. `arg` renders to a
     * byte-comparable jsonb string (null → `{}` per the baseline default); `brief` is a plain text
     * column (V2), bound as-is.
     */
    fun toColumns(t: NationTurn): Map<String, Any?> = linkedMapOf(
        "nation_id" to t.nationId,
        "officer_level" to t.officerLevel,
        "turn_idx" to t.turnIdx,
        "action_code" to t.action,
        "arg" to MetaJson.encode(t.arg ?: emptyMap<String, Any?>()),
        "brief" to t.brief,
    )
}
