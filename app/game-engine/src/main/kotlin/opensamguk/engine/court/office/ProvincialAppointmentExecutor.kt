package opensamguk.engine.court.office

import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.CourtStateStore
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.Phase
import opensamguk.logic.office.*

/**
 * 사용자 결정 2026-10-15: 지방 임명 자원비용 0, 군주 결정턴 1회(발행 단계).
 * 거절·동일 requestId 재처리는 추가 비용·효과 없음.
 * Completion writes only tenure and receipt; input, dispatcher and reservation wiring remain S2.
 */
class ProvincialAppointmentExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    sealed interface Result {
        data object SourceUnavailable : Result
        data object RequestConflict : Result
        data class Denied(val reason: OfficeAppointmentFailure) : Result
        data class Appointed(val tenure: OfficeTenure, val replay: Boolean = false) : Result
    }

    companion object {
        private const val RECEIPTS_KEY = "provincialAppointmentReceipts"
    }

    /**
     * Called in the daemon serialized unit. The server source callback must read current native
     * ruler/lifecycle and canonical jurisdiction facts, not an intake snapshot. No provider is
     * installed in production in this stage. Resource cost is zero: no resource writes.
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
        // A committed receipt describes the original execution, not today's eligibility.
        // Check it before reading mutable candidate/offer/source state. A missing tenure must
        // never turn a consumed request back into a new appointment (or a second payment).
        val request = expectedOffer.request
        val store = CourtStateStore(world, recorder)
        val tenures = store.tenures()
        val tenureId = "provincial:$requestId"
        val prior = tenures.singleOrNull { it.id == tenureId }
        val rawReceipts = world.getState().meta[RECEIPTS_KEY]
        if (rawReceipts != null && rawReceipts !is Map<*, *>) return Result.SourceUnavailable
        val priorReceipts = rawReceipts as? Map<*, *>
        if (priorReceipts?.containsKey(requestId) == true || prior != null) {
            val matches = priorReceipts?.get(requestId) == expectedOffer.toMetaValue() && prior != null &&
                prior.issuerId == request.issuerId && prior.holderId == request.candidateId &&
                prior.officeId == request.officeId && prior.jurisdictionId == request.jurisdictionId
            return if (matches) Result.Appointed(requireNotNull(prior), replay = true) else Result.RequestConflict
        }
        val projection = freshSources(world, expectedOffer)
        if (projection !is OfficeAppointmentContextProjection.Ready) return Result.SourceUnavailable
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        if (projection.sources.binding.worldId != world.worldId || projection.sources.binding.asOf != now ||
            projection.sources.unavailable.isNotEmpty() || projection.sources.knownSections != OfficeSourceKey.entries.toSet()) {
            return Result.SourceUnavailable
        }
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
        val fresh = context.copy(activeTenures = tenures)
        val completion = OfficeAppointmentExecution.complete(tenureId, expectedOffer, fresh,
            OfficeCatalog.loadClasspath(), OfficeRules.loadClasspath(), appointedTurn, acceptedTurn)
        if (completion is OfficeAppointmentCompletion.Denied) return Result.Denied(completion.reason)
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
