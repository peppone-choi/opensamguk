package opensamguk.gameapi.owner

import opensamguk.common.wire.GeneralBoolResult
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.infra.persistence.CommandResultRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

private const val CLAIM_NPC_RESULT_TYPE = "claimNpc"
private const val CLAIM_FAILED_REASON = "빙의에 실패했습니다."

sealed interface ClaimNpcRequestStatus {
    data object Pending : ClaimNpcRequestStatus
    data object Applied : ClaimNpcRequestStatus
    data class Rejected(val reason: String) : ClaimNpcRequestStatus
    data object Invalid : ClaimNpcRequestStatus
}

fun interface ClaimNpcRequestStatusReader {
    fun read(requestId: String, generalId: Int): ClaimNpcRequestStatus
}

@Service
class CommandResultClaimNpcRequestStatusReader(
    private val commandResults: CommandResultRepository,
    processWorld: GameApiProcessWorld,
) : ClaimNpcRequestStatusReader {
    private val worldId: WorldId = processWorld.worldId

    override fun read(requestId: String, generalId: Int): ClaimNpcRequestStatus {
        val payload = commandResults.findResultPayload(worldId, requestId) ?: return ClaimNpcRequestStatus.Pending
        val envelope = runCatching {
            WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), payload)
        }.getOrNull() ?: return ClaimNpcRequestStatus.Invalid
        if (envelope.requestId != requestId) return ClaimNpcRequestStatus.Invalid
        val result = (envelope.event as? TurnDaemonEvent.CommandResult)?.result as? GeneralBoolResult
            ?: return ClaimNpcRequestStatus.Invalid
        if (result.type != CLAIM_NPC_RESULT_TYPE || result.generalId != generalId) return ClaimNpcRequestStatus.Invalid
        return if (result.ok) ClaimNpcRequestStatus.Applied else ClaimNpcRequestStatus.Rejected(
            result.reason ?: CLAIM_FAILED_REASON,
        )
    }
}

@Service
class GeneralOwnershipClassifier @Autowired constructor(
    private val owners: GeneralOwnerRepository,
    private val generalBodies: GeneralOwnershipReadSource,
    private val claimStatuses: ClaimNpcRequestStatusReader,
) {
    constructor(
        owners: GeneralOwnerRepository,
        generals: GeneralReadRepository,
    ) : this(
        owners,
        RepositoryGeneralOwnershipReadSource(generals),
        ClaimNpcRequestStatusReader { _, _ -> ClaimNpcRequestStatus.Pending },
    )

    constructor(
        owners: GeneralOwnerRepository,
        generals: GeneralReadRepository,
        claimStatuses: ClaimNpcRequestStatusReader,
    ) : this(owners, RepositoryGeneralOwnershipReadSource(generals), claimStatuses)

    sealed interface Ownership {
        data class LiveOwned(
            val body: GeneralOwnershipSnapshot,
            val reservation: GeneralOwnerEntity?,
        ) : Ownership

        data class CorrelatedPending(
            val reservation: GeneralOwnerEntity,
            val body: GeneralOwnershipSnapshot,
        ) : Ownership

        data class Stale(
            val reservation: GeneralOwnerEntity,
            val disposition: StaleDisposition,
        ) : Ownership

        data object None : Ownership
    }

    sealed interface StaleDisposition {
        data object Legacy : StaleDisposition
        data object Applied : StaleDisposition
        data class Rejected(val reason: String) : StaleDisposition
        data object Invalid : StaleDisposition
        data object MissingOrNonCandidate : StaleDisposition
    }

    sealed interface RepairResult {
        data object Removed : RepairResult
        data class TerminalRejected(val reason: String) : RepairResult
        data object Changed : RepairResult
        data object NotRepairable : RepairResult
    }

    private sealed interface ReservationAssessment {
        data class Live(val body: GeneralOwnershipSnapshot) : ReservationAssessment
        data class Pending(val body: GeneralOwnershipSnapshot) : ReservationAssessment
        data class Stale(val ownership: Ownership.Stale) : ReservationAssessment
    }

    @Transactional
    fun classify(userId: Long): Ownership {
        val reservation = owners.findByUserId(userId)
        val userIdText = userId.toString()
        val liveGeneral = generalBodies.findPlayableByUserId(userIdText)
            ?.takeIf { it.isPlayableOwnedBy(userIdText) }
        val assessment = reservation?.let { assessReservation(it, userIdText) }

        if (liveGeneral != null) {
            // A typed live body wins admission, but an old terminal reservation must not keep a different
            // npc=2 body globally excluded. The compare-and-delete preserves a newer/pending replacement.
            (assessment as? ReservationAssessment.Stale)?.let { repair(it.ownership) }
            return Ownership.LiveOwned(liveGeneral, reservation)
        }

        return when (assessment) {
            null -> Ownership.None
            is ReservationAssessment.Live -> Ownership.LiveOwned(assessment.body, reservation)
            is ReservationAssessment.Pending -> Ownership.CorrelatedPending(requireNotNull(reservation), assessment.body)
            is ReservationAssessment.Stale -> assessment.ownership
        }
    }

    @Transactional
    fun repair(stale: Ownership.Stale): RepairResult {
        if (stale.disposition == StaleDisposition.Invalid) return RepairResult.NotRepairable
        if (owners.deleteIfUnchanged(stale.reservation) != 1) return RepairResult.Changed
        return when (val disposition = stale.disposition) {
            is StaleDisposition.Rejected -> RepairResult.TerminalRejected(disposition.reason)
            else -> RepairResult.Removed
        }
    }

    private fun ClaimNpcRequestStatus.toStaleDisposition(): StaleDisposition = when (this) {
        ClaimNpcRequestStatus.Applied -> StaleDisposition.Applied
        is ClaimNpcRequestStatus.Rejected -> StaleDisposition.Rejected(reason)
        ClaimNpcRequestStatus.Invalid -> StaleDisposition.Invalid
        ClaimNpcRequestStatus.Pending -> StaleDisposition.MissingOrNonCandidate
    }

    private fun assessReservation(owner: GeneralOwnerEntity, userIdText: String): ReservationAssessment {
        val requestId = owner.claimRequestId
            ?: return ReservationAssessment.Stale(Ownership.Stale(owner, StaleDisposition.Legacy))

        // Read the terminal state first, then the body it claims to describe. A successful old request
        // whose freshly-read body is back in the unowned NPC pool was released, not pending.
        val status = claimStatuses.read(requestId, owner.generalId.toInt())
        val target = generalBodies.findById(owner.generalId.toInt())
        if (target?.isPlayableOwnedBy(userIdText) == true) return ReservationAssessment.Live(target)
        if (status == ClaimNpcRequestStatus.Pending && target?.isCorrelatedCandidate() == true) {
            return ReservationAssessment.Pending(target)
        }
        return ReservationAssessment.Stale(Ownership.Stale(owner, status.toStaleDisposition()))
    }

    private fun GeneralOwnershipSnapshot.isCorrelatedCandidate(): Boolean =
        npcState == GeneralPossessionService.CLAIMABLE_NPC_STATE && (userId?.toLongOrNull() ?: 0L) <= 0L

    private fun GeneralOwnershipSnapshot.isPlayableOwnedBy(userId: String): Boolean =
        this.userId == userId && npcState < GeneralPossessionService.CLAIMABLE_NPC_STATE

}
