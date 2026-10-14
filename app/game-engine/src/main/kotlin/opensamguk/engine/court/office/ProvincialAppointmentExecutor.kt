package opensamguk.engine.court.office

import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.CourtStateStore
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.Phase
import opensamguk.logic.office.*

/** Internal baseline only: no intake, dispatcher, reservation or production cost approval. */
class ProvincialAppointmentExecutor private constructor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val fixtureCost: CostFixture?,
) {
    constructor(world: InMemoryTurnWorld, recorder: ChangeRecorder) : this(world, recorder, null)

    sealed interface Result {
        data object CostDecisionPending : Result
        data object SourceUnavailable : Result
        data object RequestConflict : Result
        data class Denied(val reason: OfficeAppointmentFailure) : Result
        data class Appointed(val tenure: OfficeTenure, val replay: Boolean = false) : Result
    }

    /** Not an approved cost or payment implementation; accessible only to explicit engine tests. */
    internal data class CostFixture(val label: String, val amount: Int) {
        init { require(label.startsWith("TEST_ONLY:") && amount > 0) }
    }

    companion object {
        private const val RECEIPTS_KEY = "provincialAppointmentReceipts"

        internal fun forTest(world: InMemoryTurnWorld, recorder: ChangeRecorder, fixture: CostFixture) =
            ProvincialAppointmentExecutor(world, recorder, fixture)
    }

    /**
     * Called in the daemon serialized unit. The server source callback must read current native
     * ruler/lifecycle and canonical jurisdiction facts, not an intake snapshot. No provider is
     * installed in production in this stage. A ready projection is not a cost approval.
     */
    fun execute(
        expectedWorldId: WorldId,
        requestId: String,
        expectedOffer: OfficeAppointmentOffer,
        appointedTurn: Long,
        acceptedTurn: Long,
        freshSources: (InMemoryTurnWorld, OfficeAppointmentOffer) -> OfficeAppointmentContextProjection,
    ): Result {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(appointedTurn >= 0 && acceptedTurn >= appointedTurn)
        if (expectedWorldId != world.worldId) return Result.SourceUnavailable
        val projection = freshSources(world, expectedOffer)
        if (projection !is OfficeAppointmentContextProjection.Ready) return Result.SourceUnavailable
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (projection.sources.binding.worldId != world.worldId || projection.sources.binding.asOf != now ||
            projection.sources.unavailable.isNotEmpty() || projection.sources.knownSections != OfficeSourceKey.entries.toSet()) {
            return Result.SourceUnavailable
        }
        val request = expectedOffer.request
        val issuer = world.getGeneralById(request.issuerId) ?: return Result.SourceUnavailable
        val candidate = world.getGeneralById(request.candidateId) ?: return Result.SourceUnavailable
        val context = projection.context
        if (context.issuerNationId != issuer.nationId || context.candidateNationId != candidate.nationId) {
            return Result.SourceUnavailable
        }
        // An accepted private offer is the source, not caller-controlled acceptance.
        if (OfficeAppointmentOffer.read(candidate.meta) != expectedOffer) return Result.SourceUnavailable
        if (CaptiveState.META_KEY in candidate.meta) {
            // Malformed custody markers also fail closed.
            runCatching { CaptiveState.read(candidate.meta) }
            return Result.Denied(OfficeAppointmentFailure.CANDIDATE_UNAVAILABLE)
        }
        val store = CourtStateStore(world, recorder)
        val tenures = store.tenures()
        val tenureId = "provincial:$requestId"
        val prior = tenures.singleOrNull { it.id == tenureId }
        if (prior != null) {
            val receipts = world.getState().meta[RECEIPTS_KEY] as? Map<*, *>
            val matches = receipts?.get(requestId) == expectedOffer.toMetaValue() && prior.issuerId == request.issuerId && prior.holderId == request.candidateId &&
                prior.officeId == request.officeId && prior.jurisdictionId == request.jurisdictionId &&
                prior.nationId == issuer.nationId
            return if (matches) Result.Appointed(prior, replay = true) else Result.RequestConflict
        }
        val fresh = context.copy(activeTenures = tenures)
        val completion = OfficeAppointmentExecution.complete(tenureId, expectedOffer, fresh,
            OfficeCatalog.loadClasspath(), OfficeRules.loadClasspath(), appointedTurn, acceptedTurn)
        if (completion is OfficeAppointmentCompletion.Denied) return Result.Denied(completion.reason)
        if (fixtureCost == null) return Result.CostDecisionPending
        val tenure = (completion as OfficeAppointmentCompletion.Appointed).tenure
        // CourtStateStore writes the existing game_env KV channel. Roll back both halves if its
        // recorder mutation gate rejects; the outer daemon unit still owns durable flush rollback.
        val worldCheckpoint = world.checkpoint()
        val recorderCheckpoint = recorder.checkpoint()
        try {
            store.saveTenures(tenures + tenure)
            val existing = world.getState().meta[RECEIPTS_KEY]
            require(existing == null || existing is Map<*, *>) { "invalid appointment receipts" }
            val receipts = linkedMapOf<String, Any?>()
            (existing as? Map<*, *>)?.forEach { (key, value) -> receipts[key as String] = value }
            receipts[requestId] = expectedOffer.toMetaValue()
            recorder.recordKv("game_env", "game_env", RECEIPTS_KEY, receipts)
            world.setGameEnvValue(RECEIPTS_KEY, receipts)
        } catch (failure: Exception) {
            world.restore(worldCheckpoint)
            recorder.restore(recorderCheckpoint)
            throw failure
        }
        return Result.Appointed(tenure)
    }
}
