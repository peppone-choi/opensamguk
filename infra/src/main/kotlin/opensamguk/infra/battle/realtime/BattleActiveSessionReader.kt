package opensamguk.infra.battle.realtime

import java.time.Instant
import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/** Internal owner-filtered rows. Public squad identity and visibility are projected separately. */
data class BattleActiveSessionRow(
    val worldId: WorldId,
    val battleId: String,
    val phase: BattleSessionPhase,
    val sessionEpoch: Long,
    val currentTick: Int,
    val latestEventSeq: Long,
    val joinDeadlineAt: Instant,
    val participantId: Int,
    val side: String,
    val authorityRevision: Long,
)

fun interface BattleActiveSessionReader {
    fun forOwner(worldId: WorldId, accountId: Int, generalId: Int, limit: Int): List<BattleActiveSessionRow>
}

/** Both account and current general must match one frozen participant in this world. */
class JdbcBattleActiveSessionReader(private val db: NamedParameterJdbcTemplate) : BattleActiveSessionReader {
    override fun forOwner(worldId: WorldId, accountId: Int, generalId: Int,
                          limit: Int): List<BattleActiveSessionRow> {
        require(accountId > 0 && generalId > 0 && limit in 1..100)
        return db.query("""
            SELECT session.world_id, session.battle_id, session.phase, session.session_epoch,
                   session.current_tick, session.latest_event_seq, session.join_deadline_at,
                   participant.participant_id, participant.side, participant.authority_revision
              FROM battle_session AS session
              JOIN battle_participant AS participant
                ON participant.world_id = session.world_id AND participant.battle_id = session.battle_id
             WHERE session.world_id = :world_id
               AND participant.account_id = :account_id
               AND participant.general_id = :general_id
               AND session.phase IN ('READY', 'JOINING', 'RUNNING', 'RESOLVING', 'RESULT_PENDING')
             ORDER BY session.join_deadline_at, session.battle_id
             LIMIT :limit
        """.trimIndent(), MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("account_id", accountId)
            .addValue("general_id", generalId)
            .addValue("limit", limit)) { row, _ ->
            BattleActiveSessionRow(WorldId(row.getInt("world_id")), row.getString("battle_id"),
                BattleSessionPhase.valueOf(row.getString("phase")), row.getLong("session_epoch"),
                row.getInt("current_tick"), row.getLong("latest_event_seq"),
                row.getTimestamp("join_deadline_at").toInstant(), row.getInt("participant_id"),
                row.getString("side"), row.getLong("authority_revision"))
        }
    }
}
