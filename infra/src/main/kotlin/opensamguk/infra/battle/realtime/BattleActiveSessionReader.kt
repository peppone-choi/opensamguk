package opensamguk.infra.battle.realtime

import java.time.Instant
import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** A real persisted session and its frozen participant, filtered by both account and current general. */
data class BattleActiveSessionRow(
    val worldId: WorldId,
    val battleId: String,
    val sourcePhase: BattleSessionPhase,
    val participantId: Int,
    val side: String,
    val authorityRevision: Long,
    val joinDeadlineAt: Instant,
    val observedAt: Instant,
)

fun interface BattleActiveSessionReader {
    fun forOwner(worldId: WorldId, accountId: Int, generalId: Int, limit: Int): List<BattleActiveSessionRow>
}

/** A missing session returns no rows; a missing/unavailable source is an error, never an empty list. */
class JdbcBattleActiveSessionReader(private val db: NamedParameterJdbcTemplate) : BattleActiveSessionReader {
    override fun forOwner(worldId: WorldId, accountId: Int, generalId: Int,
                          limit: Int): List<BattleActiveSessionRow> {
        require(accountId > 0 && generalId > 0 && limit in 1..101)
        return db.query("""
            SELECT session.world_id, session.battle_id, session.phase,
                   session.join_deadline_at, participant.participant_id,
                   participant.side, participant.authority_revision,
                   statement_timestamp() AS observed_at
              FROM battle_session AS session
              JOIN battle_participant AS participant
                ON participant.world_id = session.world_id AND participant.battle_id = session.battle_id
              JOIN battle_ticket AS ticket
                ON ticket.world_id = session.world_id AND ticket.battle_id = session.battle_id
             WHERE session.world_id = :world_id
               AND participant.account_id = :account_id
               AND participant.general_id = :general_id
               AND session.phase IN ('READY', 'JOINING', 'RUNNING', 'RESOLVING',
                                     'RESULT_PENDING', 'RESULT_BLOCKED', 'QUARANTINED')
             ORDER BY CASE WHEN session.phase = 'JOINING' THEN 0
                           WHEN session.phase = 'RUNNING' THEN 1 ELSE 2 END,
                      session.join_deadline_at, session.battle_id
             LIMIT :limit
        """.trimIndent(), MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("account_id", accountId)
            .addValue("general_id", generalId)
            .addValue("limit", limit)) { row, _ ->
            BattleActiveSessionRow(WorldId(row.getInt("world_id")), row.getString("battle_id"),
                BattleSessionPhase.valueOf(row.getString("phase")), row.getInt("participant_id"),
                row.getString("side"), row.getLong("authority_revision"),
                row.getTimestamp("join_deadline_at").toInstant(),
                row.getTimestamp("observed_at").toInstant())
        }
    }
}
