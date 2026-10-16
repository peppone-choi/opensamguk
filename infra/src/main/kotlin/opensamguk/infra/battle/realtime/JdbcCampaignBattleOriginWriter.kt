package opensamguk.infra.battle.realtime

import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.battle.realtime.*
import opensamguk.logic.input.EncounterParticipant
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.Connection

/** Uses only the flush caller's transaction, after its CAS and native general/bugok writes. */
class JdbcCampaignBattleOriginWriter {
    data class Result(
        val encounterId: String,
        val reason: String? = null,
        val detail: String? = null,
        val ownerGeneralId: Int? = null,
        val origin: CampaignBattleOriginSnapshot? = null,
    )

    fun finalizeAndInsert(
        jdbc: NamedParameterJdbcTemplate,
        worldId: WorldId,
        worldStateUpdate: Map<String, Any?>,
        drafts: List<CampaignBattleOriginDraft>,
    ): List<Result> = requireNotNull(jdbc.jdbcTemplate.execute(ConnectionCallback { connection ->
        require(!connection.autoCommit && !connection.isReadOnly) { "Origin writer requires caller-owned writable transaction" }
        val expected = (worldStateUpdate["expected_world_version"] as? Number)?.toLong()
        val epoch = (worldStateUpdate["writer_epoch"] as? Number)?.toLong()
        drafts.map { draft ->
            require(draft.worldId == worldId.value) { "Cross-world origin draft" }
            val result = if (expected == null || epoch == null) Result(draft.encounter.encounterId, "NO_WORLD_FENCE")
            else finalizeOne(connection, worldId, expected, epoch, draft)
            if (result.reason != null) log.warn(
                "campaign_battle_origin_skipped encounter={} reason={} detail={} owner={}",
                result.encounterId, result.reason, result.detail, result.ownerGeneralId)
            result
        }
    }))

    private fun finalizeOne(
        connection: Connection, worldId: WorldId, expected: Long, epoch: Long, draft: CampaignBattleOriginDraft,
    ): Result {
        val encounter = draft.encounter
        val ownerIds = (listOf(encounter.attacker) + encounter.defenders).map { it.ownerGeneralId }.distinct().sorted()
        val native = readOwners(connection, worldId, ownerIds)
        val classified = CampaignBattleOwnerClassifier.classify(worldId.value, ownerIds.toSet(), native)
        if (classified is CampaignBattleOwnerClassifier.Rejected)
            return Result(encounter.encounterId, "OWNER_AUTHORITY_INVALID", classified.detail, classified.ownerGeneralId)
        val revisions = readRevisions(connection, worldId, draft)
        val origin = try {
            val candidate = buildOrigin(draft, Math.addExact(expected, 1L), epoch, revisions,
                (classified as CampaignBattleOwnerClassifier.Accepted).owners)
            val bytes = CampaignBattleOriginCodec.encode(candidate)
            val frozen = CampaignBattleOriginCodec.decode(bytes)
            BattleOwnerAuthorityBinding.fromOrigin(frozen, native)
            frozen
        } catch (_: IllegalArgumentException) {
            return Result(encounter.encounterId, "OWNER_AUTHORITY_INVALID", "BINDING_REJECTED")
        }
        val bytes = CampaignBattleOriginCodec.encode(origin)
        val sha = CampaignBattleOriginCodec.sha256(bytes)
        if (!insertOrigin(connection, origin, bytes, sha, MetaJson.encode(draft.forces.toMetaValue()))) {
            val stored = connection.prepareStatement(
                "SELECT source_sha256 FROM campaign_battle_origin WHERE world_id = ? AND encounter_id = ?",
            ).use { statement ->
                statement.setInt(1, worldId.value); statement.setString(2, encounter.encounterId)
                statement.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
            }
            return if (stored == sha) Result(encounter.encounterId, origin = origin)
                else Result(encounter.encounterId, "ORIGIN_CONFLICT")
        }
        insertAuthorities(connection, origin)
        return Result(encounter.encounterId, origin = origin)
    }

    private fun buildOrigin(
        draft: CampaignBattleOriginDraft, committed: Long, epoch: Long, revisions: Map<Int, Long>,
        classified: Map<Int, CampaignBattleOwnerClassifier.Owner>,
    ): CampaignBattleOriginSnapshot {
        val encounter = draft.encounter
        fun participant(p: EncounterParticipant) = CampaignBattleOriginParticipant(
            p.orderId, p.ownerGeneralId, p.commanderGeneralId, p.nationId, p.bugokIds.sorted())
        val attackerIds = encounter.attacker.bugokIds.toSet()
        val units = draft.forces.units.map { unit -> CampaignBattleOriginUnit(
            TacticalV2SourceKey(TacticalV2SourceKind.RETINUE, unit.bugokId.toString()),
            if (unit.bugokId in attackerIds) BattleSide.ATTACKER else BattleSide.DEFENDER,
            unit.ownerGeneralId, unit.commanderGeneralId, unit.crewTypeId, unit.troops, unit.training,
            unit.morale, unit.fatigue, unit.provisions, unit.commanderRetainerId, revisions.getValue(unit.bugokId))
        }.sortedBy { it.sourceKey }
        val owners = classified.toSortedMap().map { (id, owner) ->
            val owned = units.filter { it.ownerGeneralId == id }
            CampaignBattleOriginOwner(id, owner.accountId, owner.npcState, owner.playable,
                owned.first().side, owned.map { it.sourceKey }.sorted(), 1, CampaignBattleAuthorityStatus.ACTIVE)
        }
        return CampaignBattleOriginSnapshot(draft.worldId, encounter.encounterId, encounter.encounterId,
            draft.forces.snapshotId, committed, epoch, encounter.topologyRevision, encounter.topologyHash,
            encounter.province.canonicalKey, encounter.approachFrom.canonicalKey, draft.tilesHash,
            committed, 1, participant(encounter.attacker), encounter.defenders.map(::participant), units, owners)
    }

    private fun readRevisions(connection: Connection, worldId: WorldId, draft: CampaignBattleOriginDraft): Map<Int, Long> {
        val ids = draft.forces.units.map { it.bugokId }.sorted()
        val array = connection.createArrayOf("integer", ids.toTypedArray())
        try {
            return connection.prepareStatement(
                "SELECT id, revision FROM general_bugok WHERE world_id = ? AND id = ANY (?) ORDER BY id FOR UPDATE",
            ).use { statement ->
                statement.setInt(1, worldId.value); statement.setArray(2, array)
                statement.executeQuery().use { rows ->
                    val result = linkedMapOf<Int, Long>()
                    while (rows.next()) {
                        val id = rows.getInt("id")
                        val revision = rows.getLong("revision")
                        require(!rows.wasNull() && revision > 0 && result.put(id, revision) == null)
                    }
                    require(result.keys.toList() == ids) { "Incomplete native origin source revisions" }
                    result
                }
            }
        } finally { array.free() }
    }

    private fun readOwners(connection: Connection, worldId: WorldId, ids: List<Int>): List<BattleOwnerNativeGeneral> {
        val idArray = connection.createArrayOf("integer", ids.toTypedArray())
        try {
            val candidates = connection.prepareStatement(
                "SELECT user_id FROM general WHERE world_id = ? AND id = ANY (?) ORDER BY id FOR UPDATE",
            ).use { statement ->
                statement.setInt(1, worldId.value); statement.setArray(2, idArray)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) rows.getString(1)?.let { add(it) } } }
            }
            val accounts = connection.createArrayOf("text", candidates.distinct().sorted().toTypedArray())
            try {
                return connection.prepareStatement(
                    "SELECT id, world_id, user_id, npc_state FROM general " +
                        "WHERE world_id = ? AND (id = ANY (?) OR user_id = ANY (?)) ORDER BY id FOR UPDATE",
                ).use { statement ->
                    statement.setInt(1, worldId.value); statement.setArray(2, idArray); statement.setArray(3, accounts)
                    statement.executeQuery().use { rows -> buildList {
                        while (rows.next()) add(BattleOwnerNativeGeneral(rows.getInt("world_id"), rows.getInt("id"),
                            rows.getString("user_id"), rows.getInt("npc_state")))
                    } }
                }
            } finally { accounts.free() }
        } finally { idArray.free() }
    }

    private fun insertOrigin(
        connection: Connection, origin: CampaignBattleOriginSnapshot, bytes: ByteArray, sha: String, forcesJson: String,
    ): Boolean = connection.prepareStatement(
        "INSERT INTO campaign_battle_origin (world_id, battle_id, encounter_id, source_bytes, source_sha256, " +
            "forces_snapshot_id, forces_json, committed_world_version, writer_epoch, topology_revision, topology_hash, " +
            "province_key, approach_key, tiles_hash, lock_generation, lock_set_revision) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (world_id, encounter_id) DO NOTHING",
    ).use { statement ->
        statement.setInt(1, origin.worldId); statement.setString(2, origin.battleId)
        statement.setString(3, origin.encounterId); statement.setBytes(4, bytes); statement.setString(5, sha)
        statement.setString(6, origin.forcesSnapshotId); statement.setString(7, forcesJson)
        statement.setLong(8, origin.committedWorldVersion); statement.setLong(9, origin.writerEpoch)
        statement.setString(10, origin.topologyRevision); statement.setString(11, origin.topologyHash)
        statement.setString(12, origin.provinceKey); statement.setString(13, origin.approachKey)
        statement.setString(14, origin.tilesHash); statement.setLong(15, origin.lockGeneration)
        statement.setLong(16, origin.lockSetRevision)
        statement.executeUpdate() == 1
    }

    private fun insertAuthorities(connection: Connection, origin: CampaignBattleOriginSnapshot) {
        connection.prepareStatement(
            "INSERT INTO campaign_battle_owner_authority (world_id, battle_id, owner_general_id, account_id, " +
                "npc_state, playable, side, controlled_source_sha256, revision, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).use { statement ->
            for (owner in origin.owners) {
                statement.setInt(1, origin.worldId); statement.setString(2, origin.battleId)
                statement.setInt(3, owner.ownerGeneralId); statement.setObject(4, owner.accountId)
                statement.setInt(5, owner.npcState); statement.setBoolean(6, owner.playable)
                statement.setString(7, owner.side.name)
                statement.setString(8, CampaignBattleOriginCodec.controlledSourceSha256(owner, origin.units))
                statement.setLong(9, owner.authorityRevision); statement.setString(10, owner.authorityStatus.name)
                check(statement.executeUpdate() == 1)
            }
        }
    }

    companion object { private val log = LoggerFactory.getLogger(JdbcCampaignBattleOriginWriter::class.java) }
}
