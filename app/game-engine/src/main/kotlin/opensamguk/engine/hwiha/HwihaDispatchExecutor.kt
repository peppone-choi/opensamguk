package opensamguk.engine.hwiha

import opensamguk.engine.turn.*
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents

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
    fun assess(request: DispatchRequest): DispatchAssessment = projection()?.let { HwihaDispatchRules.assess(request, it) }
        ?: DispatchAssessment.Rejected(DispatchFailure.STATE_UNAVAILABLE)

    fun assessAssignment(actorId: Int, assignment: HwihaCountyAssignment): DispatchAssessment = projection()?.let {
        HwihaDispatchRules.assessAssignment(actorId, assignment, it)
    } ?: DispatchAssessment.Rejected(DispatchFailure.STATE_UNAVAILABLE)

    /**
     * @param targetText the target's private record text. An NPC lord passes its reason here so that the
     *   dispatch basis stays in the target's own record (spec §14); a player lord's dispatch uses the default.
     */
    fun issue(dispatchId: String, request: DispatchRequest, targetText: String? = null): DispatchExecution {
        val assessment = assess(request)
        if (assessment is DispatchAssessment.Rejected) return reject(assessment.reason)
        assessment as DispatchAssessment.Eligible
        val now = now()
        val dispatch = HwihaDispatchState(dispatchId, request.actorId, request.targetGeneralId,
            assessment.issuer.nationId, request.countyId, now, now.plus(policy.responsePhases))
        updateMeta(world.getGeneralById(request.targetGeneralId)!!,
            assessment.target.meta + (HwihaDispatchState.META_KEY to dispatch.toMetaValue()))
        // Only the issuer and the target learn about a dispatch; it is private to both (HwihaDispatchState).
        HwihaRecords.general(world, dispatch.targetId, HwihaRecordKind.DISPATCH_RECEIVED,
            targetText ?: "발령이 도착했습니다. 기한 안에 수락하거나 거절할 수 있습니다.", refs(dispatch))
        if (humanOwned(dispatch.issuerId)) HwihaRecords.general(world, dispatch.issuerId, HwihaRecordKind.DISPATCH_ISSUED,
            "휘하 장수에게 발령을 내렸습니다.", refs(dispatch))
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
                HwihaRecords.general(world, target.id, HwihaRecordKind.DISPATCH_CANCELLED,
                    "기한이 되었지만 발령이 더 이상 유효하지 않아 벌점 없이 취소되었습니다.",
                    refs(cancelled) + ("reason" to assessment.reason.name))
            }
            return reject(assessment.reason)
        }
        assessment as DispatchAssessment.Eligible
        val target = world.getGeneralById(request.actorId)!!
        val old = HwihaDispatchState.read(target.meta)!!
        val accept = request.accept || now() >= old.dueAt
        val resolved = old.copy(status = if (accept) DispatchStatus.ACCEPTED else DispatchStatus.REFUSED)
        var meta: Map<String, Any?> = LinkedHashMap(target.meta)
        var renownRecorded = false
        if (accept) {
            meta = meta + (HwihaCountyAssignment.META_KEY to HwihaCountyAssignment(old.dispatchId, old.issuerId,
                old.nationId, old.countyId).toMetaValue())
        } else {
            // Renown is charged once, by the monthly assessment (2026-09-23 user decision: one path, tally -4).
            // A refusal still requires a readable renown state so that the charge has somewhere to land.
            try { HwihaPersonPolicyState.read(target.meta) }
                catch (_: IllegalArgumentException) { null } ?: return reject(DispatchFailure.POLICY_UNAVAILABLE)
            val tallied = RenownEvents.recordRenownEvent(meta, RenownEventSource.DISPATCH_REFUSAL,
                RenownEvents.stampOf(now().year, now().month))
            meta = tallied.meta
            renownRecorded = tallied.recorded
            val card = world.getRetainerById(assessment.card.id)!!
            world.updateRetainer(card.copy(loyalty = (card.loyalty - policy.refusalLoyaltyLoss).coerceAtLeast(0)))
        }
        meta = meta + (HwihaDispatchState.META_KEY to resolved.toMetaValue())
        updateMeta(target, meta)
        val kind = if (accept) HwihaRecordKind.DISPATCH_ACCEPTED else HwihaRecordKind.DISPATCH_REFUSED
        // A lapsed deadline accepts: the automatic expiry, or a refusal that arrived at or after the deadline.
        val lapsed = automatic || (accept && !request.accept)
        val how = if (lapsed) "기한이 지나 발령을 수락한 것으로 처리되었습니다. 다음 턴부터 부임지로 행군합니다." else null
        HwihaRecords.general(world, target.id, kind,
            how ?: if (accept) "발령을 수락했습니다. 다음 턴부터 부임지로 행군합니다."
                else "발령을 거절했습니다. 충성이 ${policy.refusalLoyaltyLoss} 줄고 다음 월단평에 발령 거절이 반영됩니다.",
            refs(resolved) + ("lapsed" to lapsed))
        if (renownRecorded) HwihaRenownEventRecorder.announce(world, target.id, RenownEventSource.DISPATCH_REFUSAL)
        // An NPC lord keeps no personal record; its reasoning is already in the target's record.
        if (humanOwned(old.issuerId)) HwihaRecords.general(world, old.issuerId, kind,
            if (accept) "발령한 장수가 부임을 수락했습니다." else "발령한 장수가 부임을 거절했습니다.",
            refs(resolved) + ("lapsed" to lapsed))
        return DispatchExecution.Applied(resolved)
    }

    private fun humanOwned(generalId: Int): Boolean =
        (world.getGeneralById(generalId)?.userId?.toLongOrNull() ?: 0) > 0

    private fun refs(dispatch: HwihaDispatchState): Map<String, Any?> = linkedMapOf(
        "dispatchId" to dispatch.dispatchId, "issuerId" to dispatch.issuerId, "targetId" to dispatch.targetId,
        "countyId" to dispatch.countyId,
    )

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
