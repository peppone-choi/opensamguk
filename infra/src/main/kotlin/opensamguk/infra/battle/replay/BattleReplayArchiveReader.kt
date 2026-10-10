package opensamguk.infra.battle.replay

import java.time.Instant
import javax.sql.DataSource
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleCheckpoint
import opensamguk.infra.battle.realtime.BattleEventRecord
import opensamguk.infra.battle.realtime.BattlePacingMode
import opensamguk.infra.battle.realtime.BattleResultRecord
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.infra.battle.realtime.JdbcBattleSessionStore
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** Stored application status is evidence about battle storage, not a campaign flush acknowledgement. */
data class BattleReplayStoredResult(
    val record: BattleResultRecord,
    val status: String,
    val appliedAt: Instant?,
)

/** Internal archive only: it carries original TEXT bytes and has no public replay/asset contract. */
data class BattleReplayArchive(
    val ticket: FrozenBattleTicket,
    val head: BattleSessionHead,
    val results: List<BattleReplayStoredResult>,
    val events: List<BattleEventRecord>,
    val checkpoint: BattleCheckpoint?,
)

fun interface BattleReplayArchiveReader {
    fun read(worldId: WorldId, battleId: String): BattleReplayArchive?
}

/** Every constituent read uses the same read-only repeatable-read transaction and world key. */
class JdbcBattleReplayArchiveReader(
    private val db: NamedParameterJdbcTemplate,
    dataSource: DataSource,
) : BattleReplayArchiveReader {
    // Reuse only the existing store's SELECT projections; never claim, mark, lease or publish.
    private val sessions = JdbcBattleSessionStore(db, dataSource)
    private val readTx = TransactionTemplate(DataSourceTransactionManager(dataSource)).apply {
        isReadOnly = true
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun read(worldId: WorldId, battleId: String): BattleReplayArchive? {
        require(battleId.isNotBlank() && battleId.length <= 128)
        return readTx.execute {
            val head = sessions.head(worldId, battleId) ?: return@execute null
            val ticket = checkNotNull(sessions.ticket(worldId, battleId)) { "battle archive ticket missing" }
            BattleReplayArchive(ticket, head, results(worldId, battleId),
                sessions.eventsAfter(worldId, battleId, 0), sessions.latestCheckpoint(worldId, battleId))
        }
    }

    private fun results(worldId: WorldId, battleId: String): List<BattleReplayStoredResult> = db.query(
        """
        SELECT result_revision, session_epoch, lease_owner, result_text, result_sha256, replay_hash,
               lock_generation, lock_set_revision, pacing_mode, status, applied_at
          FROM battle_result_outbox
         WHERE world_id = :world_id AND battle_id = :battle_id
         ORDER BY result_revision
        """.trimIndent(),
        MapSqlParameterSource().addValue("world_id", worldId.value).addValue("battle_id", battleId),
    ) { row, _ ->
        BattleReplayStoredResult(
            BattleResultRecord(worldId, battleId, row.getLong("session_epoch"), row.getString("lease_owner"),
                row.getInt("result_revision"), row.getString("result_text"), row.getString("result_sha256"),
                row.getString("replay_hash"), row.getLong("lock_generation"), row.getLong("lock_set_revision"),
                BattlePacingMode.valueOf(row.getString("pacing_mode"))),
            row.getString("status"), row.getTimestamp("applied_at")?.toInstant(),
        )
    }
}
