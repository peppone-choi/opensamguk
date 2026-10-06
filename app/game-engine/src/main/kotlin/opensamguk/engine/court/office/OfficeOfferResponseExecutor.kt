package opensamguk.engine.court.office

import opensamguk.common.world.WorldId
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.Phase
import opensamguk.logic.office.OfficeAppointmentFlow
import opensamguk.logic.office.OfficeAppointmentOffer

/** Writes a response to an existing private offer within the daemon's serialized mutation unit. */
class OfficeOfferResponseExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
) {
    /**
     * [ownerUserId] must come from authenticated intake, never from reply arguments.
     * [expectedOffer] is the complete source observed by the caller, not a durable CAS revision.
     * The caller owns admission, unit rollback and flush; this method neither issues nor appoints.
     */
    fun respond(
        expectedWorldId: WorldId,
        actorId: Int,
        ownerUserId: Int,
        expectedOffer: OfficeAppointmentOffer,
        accept: Boolean,
    ): OfficeAppointmentOffer {
        require(expectedWorldId == world.worldId) { "office response world mismatch" }
        require(actorId > 0 && ownerUserId > 0) { "invalid office response actor" }
        require(expectedOffer.request.candidateId == actorId) { "office response candidate mismatch" }
        val actor = requireNotNull(world.getGeneralById(actorId)) { "office response actor unavailable" }
        require(actor.userId?.toLongOrNull() == ownerUserId.toLong()) { "office response owner mismatch" }
        val stored = requireNotNull(OfficeAppointmentOffer.read(actor.meta)) { "office response source unavailable" }
        require(stored == expectedOffer) { "office response source changed" }
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        val resolved = OfficeAppointmentFlow.respond(stored, accept, now)
        if (resolved == stored) return stored

        val next = actor.copy(meta = actor.meta + (OfficeAppointmentOffer.META_KEY to resolved.toMetaValue()))
        // A prepared generation or tombstone must reject before the live row is replaced.
        checkNotNull(recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(actor), PerTurnOverlay.toLogicGeneral(next))) {
            "office response recording unavailable"
        }
        checkNotNull(world.applyGeneralDirtyFree(next)) { "office response actor disappeared" }
        return resolved
    }
}
