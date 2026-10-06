package opensamguk.infra.battle.realtime

import opensamguk.common.world.WorldId
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.CampaignBattleAuthorityStatus
import opensamguk.logic.battle.realtime.CampaignBattleOriginCodec
import opensamguk.logic.battle.realtime.CampaignBattleOriginOwner
import opensamguk.logic.battle.realtime.CampaignBattleOriginSnapshot
import java.sql.Connection
import java.sql.ResultSet

/** No connection or transaction ownership: C1/C2 must pass their own one JDBC snapshot. */
data class CampaignBattleCurrentRead(
    val origin: CampaignBattleOriginSnapshot,
    val sourceSha256: String,
    val currentOwners: List<CampaignBattleOriginOwner>,
    val currentBugokRevisions: Map<Int, Long>,
    val currentWorldVersion: Long,
    val currentWriterEpoch: Long,
)

class JdbcCampaignBattleOriginReader {
    fun readCommitted(connection: Connection, worldId: WorldId, battleId: String): CampaignBattleCurrentRead? {
        requireTransaction(connection)
        require(connection.isReadOnly && connection.transactionIsolation >= Connection.TRANSACTION_REPEATABLE_READ) {
            "Committed origin read requires caller-owned read-only REPEATABLE_READ transaction"
        }
        return read(connection, worldId, battleId, locked = false)
    }

    fun lockAndReadCurrent(connection: Connection, worldId: WorldId, battleId: String): CampaignBattleCurrentRead? {
        requireTransaction(connection)
        require(!connection.isReadOnly) { "Ticket insert requires a writable caller-owned transaction" }
        return read(connection, worldId, battleId, locked = true)
    }

    private fun requireTransaction(connection: Connection) {
        require(!connection.autoCommit && !connection.isClosed) {
            "Origin read requires one caller-owned active JDBC transaction"
        }
    }

    private fun read(connection: Connection, worldId: WorldId, battleId: String, locked: Boolean): CampaignBattleCurrentRead? {
        require(battleId.isNotBlank() && battleId.length <= 128)
        val world = connection.prepareStatement(
            "SELECT world_version, writer_epoch FROM world_state WHERE id = ?" + if (locked) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setInt(1, worldId.value)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                val value = rows.strictLong("world_version") to rows.strictLong("writer_epoch")
                require(!rows.next())
                value
            }
        }
        val stored = connection.prepareStatement(
            "SELECT source_bytes, source_sha256, encounter_id, forces_snapshot_id, committed_world_version, " +
                "writer_epoch, topology_revision, topology_hash, province_key, approach_key, tiles_hash, " +
                "lock_generation, lock_set_revision FROM campaign_battle_origin WHERE world_id = ? AND battle_id = ?" +
                if (locked) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setInt(1, worldId.value)
            statement.setString(2, battleId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                val bytes = requireNotNull(rows.getBytes("source_bytes"))
                val sha = requireNotNull(rows.getString("source_sha256"))
                val origin = CampaignBattleOriginCodec.decode(bytes)
                require(origin.worldId == worldId.value && origin.battleId == battleId)
                require(CampaignBattleOriginCodec.sha256(bytes) == sha)
                require(origin.encounterId == rows.getString("encounter_id") &&
                    origin.forcesSnapshotId == rows.getString("forces_snapshot_id") &&
                    origin.committedWorldVersion == rows.strictLong("committed_world_version") &&
                    origin.writerEpoch == rows.strictLong("writer_epoch") &&
                    origin.topologyRevision == rows.getString("topology_revision") &&
                    origin.topologyHash == rows.getString("topology_hash") &&
                    origin.provinceKey == rows.getString("province_key") &&
                    origin.approachKey == rows.getString("approach_key") &&
                    origin.tilesHash == rows.getString("tiles_hash") &&
                    origin.lockGeneration == rows.strictLong("lock_generation") &&
                    origin.lockSetRevision == rows.strictLong("lock_set_revision"))
                require(!rows.next())
                origin to sha
            }
        }
        val (origin, sha) = stored
        require(origin.committedWorldVersion <= world.first && origin.writerEpoch == world.second) {
            "Origin is outside the current world writer generation"
        }

        val authorities = connection.prepareStatement(
            "SELECT owner_general_id, account_id, npc_state, playable, side, controlled_source_sha256, " +
                "revision, status FROM campaign_battle_owner_authority " +
                "WHERE world_id = ? AND battle_id = ? ORDER BY owner_general_id" +
                if (locked) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setInt(1, worldId.value)
            statement.setString(2, battleId)
            statement.executeQuery().use { rows ->
                val owners = ArrayList<CampaignBattleOriginOwner>()
                while (rows.next()) {
                    val id = rows.strictInt("owner_general_id")
                    val original = origin.owners.singleOrNull { it.ownerGeneralId == id }
                        ?: error("Authority owner is absent from immutable origin")
                    val account = rows.getObject("account_id")?.let { raw ->
                        require(raw is Int && raw > 0) { "Invalid native battle account type or range" }
                        raw
                    }
                    val playable = rows.getBoolean("playable")
                    require(!rows.wasNull()) { "Null battle playable flag" }
                    val owner = CampaignBattleOriginOwner(id, account, rows.strictInt("npc_state"),
                        playable, BattleSide.valueOf(rows.getString("side")),
                        original.controlledSourceKeys, rows.strictLong("revision"),
                        CampaignBattleAuthorityStatus.valueOf(rows.getString("status")))
                    require(rows.getString("controlled_source_sha256") ==
                        CampaignBattleOriginCodec.controlledSourceSha256(original, origin.units)) {
                        "Authority source set differs from immutable origin"
                    }
                    owners += owner
                }
                owners
            }
        }
        require(authorities.size == origin.owners.size &&
            authorities.map { it.ownerGeneralId } == origin.owners.map { it.ownerGeneralId }) {
            "Incomplete battle authority"
        }

        if (locked) {
            val generalIds = (origin.owners.map { it.ownerGeneralId } +
                origin.units.map { it.commanderGeneralId }).distinct().sorted()
            connection.prepareStatement(
                "SELECT id FROM general WHERE world_id = ? AND id = ANY (?) ORDER BY id FOR UPDATE",
            ).use { statement ->
                statement.setInt(1, worldId.value)
                statement.setArray(2, connection.createArrayOf("integer", generalIds.toTypedArray()))
                statement.executeQuery().use { rows ->
                    val lockedIds = buildList { while (rows.next()) add(rows.strictInt("id")) }
                    require(lockedIds == generalIds) { "Missing native battle general" }
                }
            }
        }

        val bugokIds = origin.units.map { it.sourceKey.sourceId.toInt() }.sorted()
        val revisions = connection.prepareStatement(
            "SELECT id, revision FROM general_bugok WHERE world_id = ? AND id = ANY (?) ORDER BY id" +
                if (locked) " FOR UPDATE" else "",
        ).use { statement ->
            statement.setInt(1, worldId.value)
            statement.setArray(2, connection.createArrayOf("integer", bugokIds.toTypedArray()))
            statement.executeQuery().use { rows ->
                buildMap {
                    while (rows.next()) {
                        val id = rows.strictInt("id")
                        require(put(id, rows.strictLong("revision")) == null) {
                            "Duplicate current battle source row"
                        }
                    }
                }
            }
        }
        require(revisions.keys.toList() == bugokIds && revisions.values.all { it > 0 }) {
            "Incomplete or invalid current battle source revisions"
        }
        return CampaignBattleCurrentRead(origin, sha, authorities, revisions, world.first, world.second)
    }

    private fun ResultSet.strictInt(column: String): Int = getInt(column).also {
        require(!wasNull()) { "Null battle source $column" }
    }

    private fun ResultSet.strictLong(column: String): Long = getLong(column).also {
        require(!wasNull()) { "Null battle source $column" }
    }
}
