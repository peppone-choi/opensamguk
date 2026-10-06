package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.util.Base64
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2Deployment
import opensamguk.logic.battle.realtime.TacticalV2DeploymentCodec
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind
import opensamguk.logic.battle.realtime.TacticalV2UnitSource

/** Typed v2 ticket field. The campaign producer must separately bind source revisions and locks. */
data class BattleV2PlacementPin(
    val schemaVersion: Int,
    val boardId: Int,
    val terrainSha256: String,
    val sourceCount: Int,
    val deploymentBase64: String,
    val deploymentSha256: String,
) {
    init {
        require(schemaVersion == 2 && boardId in 0..213 && sourceCount > 0)
        require(terrainSha256.matches(Regex("[0-9a-f]{64}")) &&
            deploymentSha256.matches(Regex("[0-9a-f]{64}")))
    }

    /** Expected spawn cells must be independently derived from the pinned board, never this payload. */
    fun decode(expectedAllowedCells: Map<BattleSide, Set<TacticalV2Cell>>): TacticalV2Deployment {
        val bytes = Base64.getDecoder().decode(deploymentBase64)
        require(sha(bytes) == deploymentSha256) { "v2 deployment pin mismatch" }
        return TacticalV2DeploymentCodec.decode(bytes, boardId, terrainSha256,
            expectedAllowedCells).also { require(it.units.size == sourceCount) }
    }

    companion object {
        fun from(deployment: TacticalV2Deployment) = TacticalV2DeploymentCodec.encode(deployment).let { bytes ->
            BattleV2PlacementPin(2, deployment.boardId, deployment.terrainSha256,
                deployment.units.size, Base64.getEncoder().encodeToString(bytes), sha(bytes))
        }

        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** JOINING-only move intent; the server resolves ownership and cell occupancy at admission. */
data class BattleV2DeploymentMoveWire(
    val schemaVersion: Int,
    val t: String,
    val clientCommandId: String,
    val sourceKey: TacticalV2SourceKey,
    val targetCell: TacticalV2Cell,
    val expectedEpoch: Long,
    val expectedAuthorityRevision: Long,
    val expectedDeploymentRevision: Long,
) {
    init {
        require(schemaVersion == 2 && t == "DEPLOYMENT_MOVE")
        require(clientCommandId.isNotBlank() && clientCommandId.length <= 128)
        require(expectedEpoch > 0 && expectedAuthorityRevision >= 0 && expectedDeploymentRevision >= 0)
    }
}

/** The v2 socket may send exact placement only for sources controlled by this participant. */
data class BattleV2OwnUnitPlacement(
    val sourceKey: TacticalV2SourceKey,
    val cell: TacticalV2Cell,
    val initialTroops: Int,
)

data class BattleV2PlacementSnapshot(
    val schemaVersion: Int,
    val battleId: String,
    val sessionEpoch: Long,
    val deploymentRevision: Long,
    val ownUnits: List<BattleV2OwnUnitPlacement>,
) {
    init {
        require(schemaVersion == 2 && battleId.isNotBlank() && sessionEpoch > 0 && deploymentRevision >= 0)
        require(ownUnits.map { it.sourceKey }.distinct().size == ownUnits.size)
    }
}

/** Resolve one owner's real corps units without granting a whole side or city AI unit. */
object BattleV2AuthorityScopes {
    fun resolve(participants: List<FrozenBattleParticipant>,
                units: List<TacticalV2UnitSource>): Map<Int, Set<TacticalV2SourceKey>> {
        require(participants.map { it.generalId }.distinct().size == participants.size)
        val byGeneral = participants.associateBy { it.generalId }
        units.filter { it.key.kind == TacticalV2SourceKind.RETINUE }.forEach { unit ->
            byGeneral[unit.ownerGeneralId]?.let { participant ->
                require(participant.side == unit.side.name) {
                    "one owner cannot control opposing battle sides"
                }
            }
        }
        return participants.associate { participant ->
            participant.participantId to units.asSequence()
                .filter { it.key.kind == TacticalV2SourceKind.RETINUE &&
                    it.ownerGeneralId == participant.generalId && it.side.name == participant.side }
                .map { it.key }.toSet()
        }
    }
}
