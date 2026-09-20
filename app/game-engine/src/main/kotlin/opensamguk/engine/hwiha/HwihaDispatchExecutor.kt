package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*

sealed interface DispatchExecution {
    data class Applied(val dispatch: HwihaDispatchState) : DispatchExecution
    data class Rejected(val reason: DispatchFailure) : DispatchExecution
}

/** Daemon transition. The caller owns intake authentication, scheduling and atomic result flush. */
class HwihaDispatchExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val policy: HwihaDispatchPolicy = HwihaDispatchPolicy(),
) {
    fun issue(dispatchId: String, request: DispatchRequest): DispatchExecution {
        val assessment = projection()?.let { HwihaDispatchRules.assess(request, it) }
            ?: return reject(DispatchFailure.STATE_UNAVAILABLE)
        if (assessment is DispatchAssessment.Rejected) return reject(assessment.reason)
        assessment as DispatchAssessment.Eligible
        val now = now()
        val dispatch = HwihaDispatchState(dispatchId, request.actorId, request.targetGeneralId,
            assessment.issuer.nationId, request.countyId, now, now.plus(policy.responsePhases))
        updateMeta(world.getGeneralById(request.targetGeneralId)!!,
            assessment.target.meta + (HwihaDispatchState.META_KEY to dispatch.toMetaValue()))
        return DispatchExecution.Applied(dispatch)
    }

    /** At the deadline, acceptance takes precedence over a late refusal. */
    fun reply(request: DispatchReplyRequest): DispatchExecution = resolve(request, automatic = false)

    /** Stable target order; a second call cannot reapply refusal costs or acceptance. */
    fun expireDue(): List<DispatchExecution> {
        if (world.ruleProfile != RuleProfile.HWIHA) return emptyList()
        return world.listGenerals().sortedBy { it.id }.mapNotNull { general ->
            val dispatch = try { HwihaDispatchState.read(general.meta) } catch (_: IllegalArgumentException) { return@mapNotNull reject(DispatchFailure.STATE_UNAVAILABLE) }
            if (dispatch == null || dispatch.status != DispatchStatus.PENDING || now() < dispatch.dueAt) null
            else resolve(DispatchReplyRequest(general.id, dispatch.dispatchId, true), automatic = true)
        }
    }

    private fun resolve(request: DispatchReplyRequest, automatic: Boolean): DispatchExecution {
        val projection = projection() ?: return reject(DispatchFailure.STATE_UNAVAILABLE)
        val assessment = HwihaDispatchRules.assessReply(request, now(), projection)
        if (assessment is DispatchAssessment.Rejected) {
            // A lapsed order cannot resurrect a lost bond, a captured county or a changed owner.
            if (automatic && assessment.reason in setOf(DispatchFailure.NOT_LORD, DispatchFailure.ACTOR_NOT_FOUND,
                    DispatchFailure.TARGET_NOT_HUMAN, DispatchFailure.NOT_DIRECT_RETAINER, DispatchFailure.DIFFERENT_NATION,
                    DispatchFailure.INVALID_COUNTY, DispatchFailure.COUNTY_OCCUPIED, DispatchFailure.RELATION_CHANGED)) {
                val target = world.getGeneralById(request.actorId) ?: return reject(assessment.reason)
                val old = HwihaDispatchState.read(target.meta) ?: return reject(assessment.reason)
                if (old.targetId != target.id || old.dispatchId != request.dispatchId || old.status != DispatchStatus.PENDING)
                    return reject(assessment.reason)
                val cancelled = old.copy(status = DispatchStatus.CANCELLED)
                updateMeta(target, target.meta + (HwihaDispatchState.META_KEY to cancelled.toMetaValue()))
            }
            return reject(assessment.reason)
        }
        assessment as DispatchAssessment.Eligible
        val target = world.getGeneralById(request.actorId)!!
        val old = HwihaDispatchState.read(target.meta)!!
        val accept = request.accept || now() >= old.dueAt
        val resolved = old.copy(status = if (accept) DispatchStatus.ACCEPTED else DispatchStatus.REFUSED)
        val meta = LinkedHashMap(target.meta)
        if (accept) {
            meta[HwihaCountyAssignment.META_KEY] = HwihaCountyAssignment(old.dispatchId, old.issuerId,
                old.nationId, old.countyId).toMetaValue()
        } else {
            val personPolicy = try { HwihaPersonPolicyState.read(target.meta) }
                catch (_: IllegalArgumentException) { null } ?: return reject(DispatchFailure.POLICY_UNAVAILABLE)
            meta[HwihaPersonPolicyState.META_KEY] = personPolicy.copy(
                renownCapacity = (personPolicy.renownCapacity - policy.refusalRenownLoss).coerceAtLeast(0)).toMetaValue()
            val card = world.getRetainerById(assessment.card.id)!!
            world.updateRetainer(card.copy(loyalty = (card.loyalty - policy.refusalLoyaltyLoss).coerceAtLeast(0)))
        }
        meta[HwihaDispatchState.META_KEY] = resolved.toMetaValue()
        updateMeta(target, meta)
        return DispatchExecution.Applied(resolved)
    }

    private fun projection(): HwihaDispatchProjection? = try {
        HwihaDispatchProjection(world.ruleProfile, world.listGenerals().map {
            DispatchPerson(it.id, it.nationId, HwihaLordStatus.read(it.meta),
                (it.userId?.toLongOrNull() ?: 0) > 0, it.meta)
        }, world.listRetainers().mapNotNull { card -> card.generalId?.let {
            DispatchRetainer(card.id, card.masterGeneralId, it, card.loyalty)
        } }, world.listCities().filter { it.id in world.administrativeCountyIds }.map { DispatchCounty(it.id, it.nationId) })
    } catch (_: IllegalArgumentException) { null }

    private fun now(): HwihaPhase = world.getState().let { HwihaPhase(it.currentYear, it.currentMonth, it.currentPhase) }
    private fun updateMeta(before: TurnGeneral, meta: Map<String, Any?>) {
        val after = before.copy(meta = meta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }
    private fun reject(reason: DispatchFailure) = DispatchExecution.Rejected(reason)
}
