package opensamguk.infra.battle.realtime

import opensamguk.common.world.WorldId
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityBinding
import opensamguk.logic.battle.realtime.BattleOwnerAuthorityCurrentSource
import opensamguk.logic.battle.realtime.BattleOwnerNativeGeneral
import opensamguk.logic.battle.realtime.CampaignBattleAuthorityStatus
import opensamguk.logic.battle.realtime.CampaignBattleOriginCodec
import opensamguk.logic.battle.realtime.CampaignBattleOriginSnapshot
import java.sql.Connection
import javax.sql.DataSource

/** One process-world, caller-owned read-only snapshot. Never reserves, repairs, writes, or inserts a ticket. */
class JdbcBattleOwnerOwnershipSource(
    private val processWorld: WorldId,
    private val fixedDataSource: DataSource? = null,
    private val fixedOriginReader: JdbcCampaignBattleOriginReader? = null,
) : BattleOwnerAuthorityCurrentSource {
    override fun read(expected: BattleOwnerAuthorityBinding): BattleOwnerAuthorityBinding? {
        return try {
            val source = fixedDataSource ?: return null
            val reader = fixedOriginReader ?: return null
            if (expected.worldId != processWorld) return null
            source.connection.use { connection ->
                connection.isReadOnly = true
                connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
                connection.autoCommit = false
                try {
                    val current = reader.readCommitted(connection, processWorld, expected.battleId) ?: return null
                    val origin = current.origin
                    require(origin.worldId == processWorld.value && origin.battleId == expected.battleId)
                    require(current.sourceSha256 == expected.originSha256 &&
                        CampaignBattleOriginCodec.sha256(CampaignBattleOriginCodec.encode(origin)) == current.sourceSha256)
                    require(current.currentWorldVersion >= origin.committedWorldVersion &&
                        current.currentWriterEpoch == origin.writerEpoch)
                    require(current.currentOwners == origin.owners &&
                        current.currentOwners.all { it.authorityStatus == CampaignBattleAuthorityStatus.ACTIVE })
                    val revisions = origin.units.associate { it.sourceKey.sourceId.toInt() to it.sourceRevision }
                    require(current.currentBugokRevisions == revisions && revisions.values.all { it > 0 })
                    val native = readNativeOwners(connection, origin)
                    BattleOwnerAuthorityBinding.fromOrigin(origin, native)
                        .singleOrNull { it.ownerGeneralId == expected.ownerGeneralId }
                        ?.takeIf { expected.matches(it) }
                } finally {
                    connection.rollback()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readNativeOwners(connection: Connection, origin: CampaignBattleOriginSnapshot): List<BattleOwnerNativeGeneral> {
        val ownerIds = origin.owners.map { it.ownerGeneralId }.sorted()
        val accounts = origin.owners.mapNotNull { it.accountId?.toString() }.sorted()
        val ids = connection.createArrayOf("integer", ownerIds.toTypedArray())
        try {
            val users = connection.createArrayOf("text", accounts.toTypedArray())
            try {
                return connection.prepareStatement(
                    "SELECT id, world_id, user_id, npc_state FROM general " +
                        "WHERE world_id = ? AND (id = ANY (?) OR user_id = ANY (?)) ORDER BY id",
                ).use { statement ->
                    statement.setInt(1, processWorld.value)
                    statement.setArray(2, ids)
                    statement.setArray(3, users)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                val id = rows.getInt("id")
                                require(!rows.wasNull() && id > 0)
                                val worldId = rows.getInt("world_id")
                                require(!rows.wasNull() && worldId == processWorld.value)
                                val userId = rows.getString("user_id")
                                val npcState = rows.getInt("npc_state")
                                require(!rows.wasNull() && npcState >= 0)
                                add(BattleOwnerNativeGeneral(worldId, id, userId, npcState))
                            }
                        }
                    }
                }
            } finally {
                users.free()
            }
        } finally {
            ids.free()
        }
    }
}
