package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.domestic.CountyWorks
import opensamguk.logic.domestic.DomesticAssessment
import opensamguk.logic.domestic.DomesticFailure
import opensamguk.logic.domestic.DomesticRules
import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.WorkReductionState
import opensamguk.logic.domestic.WorkRequest
import opensamguk.logic.input.Phase

/** Applies only queued county fortifications, at a later phase boundary, with no resource debit. */
class WorkReductionExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: DomesticContext,
) {
    fun applyPending(countyId: Int, now: Phase) {
        val city = world.getCityById(countyId) ?: return
        val state = try { WorkReductionState.read(city.meta) } catch (_: IllegalArgumentException) { return } ?: return
        val order = state.pending ?: return
        if (now <= order.requestedAt) return
        val actor = world.getGeneralById(order.actorId)
        val assessment = DomesticRules.assessReduce(WorkRequest(order.actorId, countyId, DomesticWork.FORTIFICATION),
            context.projection(world), executingRequestId = order.requestId)
        val works = try { CountyWorks.read(city.meta) } catch (_: IllegalArgumentException) { null }
        val fortification = works?.completed?.singleOrNull { it.work == DomesticWork.FORTIFICATION && it.edgeId == null }
        val reason = when {
            actor?.userId?.toLongOrNull() != order.ownerUserId.toLong() -> "FORBIDDEN"
            actor?.nationId != order.nationId || city.nationId != order.nationId -> "INVALID_COUNTY"
            assessment is DomesticAssessment.Rejected -> assessment.reason.name
            fortification?.completedAt != order.completedAt -> "WORK_NOT_COMPLETED"
            else -> null
        }
        var meta = city.meta.withKey(WorkReductionState.META_KEY, state.resolve(now, reason).toMetaValue())
        if (reason == null) {
            val remaining = CountyWorks(null, checkNotNull(works).completed.filterNot {
                it.work == DomesticWork.FORTIFICATION && it.edgeId == null
            })
            meta = meta.withKey(CountyWorks.META_KEY, remaining.toMetaValue())
        }
        val next = city.copy(
            defence = if (reason == null) (city.defence - 500).coerceAtLeast(0) else city.defence,
            wall = if (reason == null) (city.wall - 500).coerceAtLeast(0) else city.wall,
            meta = meta,
        )
        recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(next))
        world.applyCityDirtyFree(next)
        val failureText = reason?.let { code -> DomesticFailure.entries.firstOrNull { it.name == code }?.message
            ?: "감축 요청자의 소유권이 바뀌었습니다." }
        val text = if (reason == null) "${city.name}의 성방을 감축했습니다."
            else "${city.name}의 성방을 감축하지 못했습니다 — $failureText"
        if (actor != null) world.pushLog(LogEntryDraft(scope = "general", category = "action", text = text,
            generalId = actor.id, nationId = actor.nationId))
    }
}
