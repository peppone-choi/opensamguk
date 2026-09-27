package opensamguk.infra.persistence

import opensamguk.logic.record.EventTurn
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** Bootstrap the active turn's in-memory event ordinal counter from committed rows. */
class GameEventOrdinalRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun maxCommitted(worldId: Int, turn: EventTurn): Int? {
        require(worldId > 0)
        return jdbc.queryForObject(
            """SELECT MAX(occurred_ordinal) FROM game_event
               WHERE world_id = :world_id AND occurred_year = :year AND occurred_month = :month
                 AND occurred_phase = :phase""",
            mapOf("world_id" to worldId, "year" to turn.year, "month" to turn.month, "phase" to turn.phase),
            Int::class.java,
        )
    }
}
