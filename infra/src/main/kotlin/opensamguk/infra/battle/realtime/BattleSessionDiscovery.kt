package opensamguk.infra.battle.realtime

import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

data class BattleSessionRef(val worldId: WorldId, val battleId: String) {
    init { require(battleId.isNotBlank()) }
}

fun interface BattleSessionDiscovery {
    fun claimable(limit: Int): List<BattleSessionRef>
}

/** Read-only candidate scan. BattleSessionStore.claimEpoch makes ownership atomic. */
class JdbcBattleSessionDiscovery(private val db: NamedParameterJdbcTemplate) : BattleSessionDiscovery {
    override fun claimable(limit: Int): List<BattleSessionRef> {
        require(limit in 1..1000)
        return db.query("""
            SELECT world_id, battle_id
              FROM battle_session
             WHERE phase IN ('READY', 'JOINING', 'RUNNING', 'RESOLVING')
               AND (lease_until IS NULL OR lease_until < clock_timestamp())
               AND (phase = 'RESOLVING' OR deadline_at > clock_timestamp() OR EXISTS (
                   SELECT 1 FROM battle_ticket AS ticket
                    WHERE ticket.world_id = battle_session.world_id
                      AND ticket.battle_id = battle_session.battle_id
                      AND ticket.pacing_mode = 'ACCELERATED_NPC'))
             ORDER BY deadline_at, world_id, battle_id
             LIMIT :limit
        """.trimIndent(), MapSqlParameterSource().addValue("limit", limit)) { row, _ ->
            BattleSessionRef(WorldId(row.getInt("world_id")), row.getString("battle_id"))
        }
    }
}
