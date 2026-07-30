package opensamguk.infra.persistence

import opensamguk.logic.domain.Diplomacy
import java.sql.ResultSet

/**
 * Logic `Diplomacy` <-> DB `diplomacy` row mapper.
 *
 * V1 baseline `diplomacy` stores a directional pair: `src_nation_id, dest_nation_id, state_code,
 * term, casualties` (+ id/is_dead/is_showing/meta). Logic maps `casualties` to [Diplomacy.dead].
 */
object DiplomacyRowMapper {

    /** Map a JDBC column map to a logic [Diplomacy]. */
    fun fromRow(row: Map<String, Any?>): Diplomacy = Diplomacy(
        me = intOf(row["src_nation_id"]),
        you = intOf(row["dest_nation_id"]),
        state = intOf(row["state_code"]),
        term = intOf(row["term"]),
        dead = intOf(row["casualties"]),
    )

    /** Map a [ResultSet] (current row) to a logic [Diplomacy]. */
    fun fromResultSet(rs: ResultSet): Diplomacy = Diplomacy(
        me = rs.getInt("src_nation_id"),
        you = rs.getInt("dest_nation_id"),
        state = rs.getInt("state_code"),
        term = rs.getInt("term"),
        dead = rs.getInt("casualties"),
    )

    /** Map a logic [Diplomacy] back to a column map ready for binding. */
    fun toColumns(d: Diplomacy): Map<String, Any?> = linkedMapOf(
        "src_nation_id" to d.me,
        "dest_nation_id" to d.you,
        "state_code" to d.state,
        "term" to d.term,
        "casualties" to d.dead,
    )
}
