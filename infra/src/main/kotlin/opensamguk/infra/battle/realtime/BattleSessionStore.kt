package opensamguk.infra.battle.realtime

import opensamguk.common.world.WorldId
import java.time.Instant

enum class BattleSessionPhase { READY, JOINING, RUNNING, RESOLVING, RESULT_PENDING, APPLIED, RESULT_BLOCKED, QUARANTINED }
enum class BattlePacingMode { REALTIME, ACCELERATED_NPC }
enum class BattleCommandVerdict { ACCEPTED, REJECTED }

data class FrozenBattleParticipant(
    val participantId: Int,
    val accountId: Int,
    val generalId: Int,
    val side: String,
    val authorityRevision: Long,
) {
    init {
        require(participantId > 0 && accountId > 0 && generalId > 0 && side in setOf("ATTACKER", "DEFENDER"))
        require(authorityRevision >= 0)
    }
}

/** The campaign handoff's immutable bytes and pins. The caller supplies the already committed handoff. */
data class FrozenBattleTicket(
    val worldId: WorldId,
    val battleId: String,
    val payloadJson: String,
    val payloadSha256: String,
    val ruleSha256: String,
    val catalogSha256: String,
    val terrainSha256: String,
    val seed: Long,
    val lockGeneration: Long,
    val lockSetRevision: Long,
    val joinDeadlineAt: Instant,
    val deadlineAt: Instant,
    val participants: List<FrozenBattleParticipant>,
    val pacingMode: BattlePacingMode = if (participants.isEmpty()) BattlePacingMode.ACCELERATED_NPC else BattlePacingMode.REALTIME,
) {
    init {
        require(battleId.isNotBlank() && battleId.length <= 128)
        require(listOf(payloadSha256, ruleSha256, catalogSha256, terrainSha256)
            .all { it.matches(Regex("[0-9a-f]{64}")) })
        require(lockGeneration >= 0 && lockSetRevision >= 0)
        require(joinDeadlineAt.isBefore(deadlineAt))
        require(participants.map { it.participantId }.distinct().size == participants.size)
        require(participants.map { it.accountId }.distinct().size == participants.size)
        require(participants == participants.sortedBy { it.participantId })
        require(pacingMode == if (participants.isEmpty()) BattlePacingMode.ACCELERATED_NPC else BattlePacingMode.REALTIME) {
            "battle pacing mode must match frozen human eligibility"
        }
    }
}

data class BattleSessionHead(
    val worldId: WorldId,
    val battleId: String,
    val phase: BattleSessionPhase,
    val sessionEpoch: Long,
    val currentTick: Int,
    val latestEventSeq: Long,
    val latestSnapshotSeq: Long,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
    val joinDeadlineAt: Instant,
    val deadlineAt: Instant,
)

data class BattleCommandRecord(
    val worldId: WorldId,
    val battleId: String,
    val participantId: Int,
    val clientCommandId: String,
    val intentSha256: String,
    val expectedEpoch: Long,
    val expectedAuthorityRevision: Long,
    val issuedTick: Int,
    val side: String,
    val intentJson: String,
) {
    init {
        require(participantId > 0 && clientCommandId.isNotBlank() && clientCommandId.length <= 128)
        require(intentSha256.matches(Regex("[0-9a-f]{64}")) && expectedEpoch >= 0)
        require(expectedAuthorityRevision >= 0 && issuedTick >= 0)
        require(side in setOf("ATTACKER", "DEFENDER"))
    }
}

data class BattleCommandReceipt(
    val clientCommandId: String,
    val verdict: BattleCommandVerdict,
    val reasonCode: String?,
    val serverTick: Int,
    val effectiveTick: Int?,
    val eventSeq: Long?,
    val authorityRevision: Long,
    val replayed: Boolean = false,
)

sealed interface CommandAdmission {
    data class Receipt(val value: BattleCommandReceipt) : CommandAdmission
    data object IdempotencyConflict : CommandAdmission
}

data class BattleEventRecord(
    val eventSeq: Long,
    val sessionEpoch: Long,
    val tick: Int,
    val effectiveTick: Int,
    val type: String,
    val payloadJson: String,
    val payloadSha256: String,
)

data class BattleTransition(
    val worldId: WorldId,
    val battleId: String,
    val sessionEpoch: Long,
    val leaseOwner: String,
    val transitionId: String,
    val type: String,
    val participantId: Int?,
    val tick: Int,
    val effectiveTick: Int,
    val payloadJson: String,
    val payloadSha256: String,
) {
    init {
        require(sessionEpoch > 0 && leaseOwner.isNotBlank() && transitionId.isNotBlank())
        require(transitionId.length <= 128 && type in setOf("HUMAN_JOIN", "HUMAN_LEFT", "AI_TAKEOVER",
            "DEPLOYMENT_SET", "SESSION_STARTED", "GATE_OPENED", "GATE_CLOSED"))
        require(tick >= 0 && effectiveTick >= tick)
        require(payloadSha256.matches(Regex("[0-9a-f]{64}")))
    }
}

data class BattleCheckpoint(
    val worldId: WorldId,
    val battleId: String,
    val sessionEpoch: Long,
    val leaseOwner: String,
    val tick: Int,
    val eventSeq: Long,
    val stateHash: String,
    val compressedState: ByteArray,
) {
    init {
        require(sessionEpoch >= 0 && leaseOwner.isNotBlank() && tick >= 0 && eventSeq >= 0)
        require(stateHash.matches(Regex("[0-9a-f]{64}")) && compressedState.isNotEmpty())
    }
}

data class BattleResultRecord(
    val worldId: WorldId,
    val battleId: String,
    val sessionEpoch: Long,
    val leaseOwner: String,
    val resultRevision: Int,
    val resultJson: String,
    val resultSha256: String,
    val replayHash: String,
    val lockGeneration: Long,
    val lockSetRevision: Long,
    val pacingMode: BattlePacingMode = BattlePacingMode.REALTIME,
) {
    init {
        require(sessionEpoch >= 0 && leaseOwner.isNotBlank() && resultRevision > 0 &&
            lockGeneration >= 0 && lockSetRevision >= 0)
        require(resultSha256.matches(Regex("[0-9a-f]{64}")) && replayHash.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Only battle_* tables may be written by this port. All mutating methods are atomic and epoch fenced. */
interface BattleSessionStore {
    fun create(ticket: FrozenBattleTicket): Boolean
    fun ticket(worldId: WorldId, battleId: String): FrozenBattleTicket?
    fun head(worldId: WorldId, battleId: String): BattleSessionHead?
    fun claimEpoch(worldId: WorldId, battleId: String, owner: String, leaseMillis: Long): BattleSessionHead?
    fun renewLease(worldId: WorldId, battleId: String, owner: String,
                   sessionEpoch: Long, leaseMillis: Long): Boolean
    fun startRun(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long): Boolean
    fun admit(command: BattleCommandRecord): CommandAdmission
    fun appendTransition(transition: BattleTransition): Long?
    /** Advances exactly one 100ms tick only if no new input was committed after the actor read its event tail. */
    fun advanceTick(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long,
                    expectedTick: Int, expectedEventSeq: Long): Boolean
    /** Atomically records the terminal logical tick and closes command admission. */
    fun advanceResolvedTick(worldId: WorldId, battleId: String, owner: String, sessionEpoch: Long,
                            expectedTick: Int, expectedEventSeq: Long): Boolean
    fun checkpoint(checkpoint: BattleCheckpoint): Boolean
    fun eventsAfter(worldId: WorldId, battleId: String, eventSeq: Long): List<BattleEventRecord>
    fun latestCheckpoint(worldId: WorldId, battleId: String): BattleCheckpoint?
    fun publishResult(result: BattleResultRecord): Boolean
    fun pendingResults(worldId: WorldId, limit: Int): List<BattleResultRecord>
    fun markApplied(worldId: WorldId, battleId: String, resultRevision: Int): Boolean
    fun markBlocked(worldId: WorldId, battleId: String, resultRevision: Int, reason: String): Boolean
}
