package opensamguk.engine.hwiha

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand.HwihaCourtInput
import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** Deferred results are emitted as sequence 2 in the same flush that consumes the issuer queue. */
data class HwihaCourtExecution(val requestId: String, val ownerUserId: Int, val result: CommandLifecycleResult)

class HwihaCourtHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val domesticContext: HwihaDomesticContext = HwihaDomesticContext(),
) {
    private val executor = HwihaDispatchExecutor(world, recorder)
    private val domestic by lazy { HwihaDomesticHandler(world, recorder, domesticContext) }
    private val executions = mutableListOf<HwihaCourtExecution>()

    fun handle(command: HwihaCourtInput): CommandLifecycleResult {
        var outcome: CommandLifecycleResult? = null
        val registry = HwihaInputRegistry(HwihaInputCatalog.load(), mapOf(
            "action.enlist" to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "출사는 개인 행동 예약으로 입력해야 합니다.") },
            HwihaDeployInput.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "본인 출병은 개인 행동 예약으로 입력해야 합니다.") },
            "court.dispatch" to InputHandler { outcome = handleKnown(command) },
            "court.dispatchReply" to InputHandler { outcome = handleKnown(command) },
            // Standing inputs share this immediate channel: they never occupy a 12-phase slot (§5.1).
            HwihaDomesticInput.PLACEMENT to InputHandler { outcome = domestic.handle(command) },
            HwihaDomesticInput.POLICY to InputHandler { outcome = domestic.handle(command) },
            HwihaDomesticInput.WORK to InputHandler { outcome = domestic.handle(command) },
        ))
        return when (val resolution = registry.resolve(world.ruleProfile, command.inputId)) {
            is InputResolution.Rejected -> result(command.generalId, command.inputId, false,
                resolution.reason.name, resolution.reason.message)
            is InputResolution.Resolved -> { resolution.handler.handle(); checkNotNull(outcome) }
        }
    }

    private fun handleKnown(command: HwihaCourtInput): CommandLifecycleResult {
        fun deny(code: String, reason: String) = result(command.generalId, command.inputId, false, code, reason)
        if (world.ruleProfile != RuleProfile.HWIHA) return deny("WRONG_RULE_PROFILE", "이 월드의 규칙에서 사용할 수 없는 입력입니다.")
        if (!command.requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return deny("INVALID_REQUEST", "입력 식별자가 올바르지 않습니다.")
        val actor = world.getGeneralById(command.generalId) ?: return deny("ACTOR_NOT_FOUND", "장수를 찾을 수 없습니다.")
        if (command.ownerUserId <= 0 || actor.userId?.toLongOrNull() != command.ownerUserId.toLong())
            return deny("FORBIDDEN", "자신의 장수만 조작할 수 있습니다.")
        return when (command.inputId) {
            "court.dispatch" -> {
                val request = HwihaDispatchInput.parse(actor.id, command.argJson) ?: return deny("INVALID_REQUEST", "발령 대상과 목적지를 확인해 주세요.")
                val existing = try { HwihaQueuedDispatch.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny("STATE_UNAVAILABLE", "저장된 발령 대기 상태를 확인할 수 없습니다.")
                }
                if (existing != null) return deny("ALREADY_QUEUED", "다음 턴에 실행할 발령이 이미 있습니다.")
                val assessment = executor.assess(request)
                if (assessment is DispatchAssessment.Rejected) return deny(assessment.reason.name, assessment.reason.message)
                val queued = HwihaQueuedDispatch(command.requestId, command.ownerUserId, request.targetGeneralId, request.countyId)
                updateMeta(actor, actor.meta + (HwihaQueuedDispatch.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            "court.dispatchReply" -> {
                val request = HwihaDispatchReplyInput.parse(actor.id, command.argJson) ?: return deny("INVALID_REQUEST", "발령 응답을 확인해 주세요.")
                when (val applied = executor.reply(request)) {
                    is DispatchExecution.Applied -> result(actor.id, command.inputId, true)
                    is DispatchExecution.Rejected -> deny(applied.reason.name, applied.reason.message)
                }
            }
            else -> deny("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
    }

    /** Runs beside, not instead of, the issuer's personal action. Lifecycle controls phase eligibility. */
    fun onIssuerTurn(generalId: Int) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        val actor = world.getGeneralById(generalId) ?: return
        val queued = HwihaQueuedDispatch.read(actor.meta)
        if (queued == null) {
            HwihaNpcDispatchSelector.select(world, generalId, executor)?.let { request ->
                if (executor.issue(HwihaNpcDispatchSelector.dispatchId(world, request), request) is DispatchExecution.Applied) {
                    world.pushLog(LogEntryDraft(scope = "general", category = "action",
                        text = "담당 장수가 없는 아군 현의 첫 부임 대상으로 발령되었습니다.",
                        generalId = request.targetGeneralId, nationId = actor.nationId))
                }
            }
            return
        }
        val result = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, "court.dispatch", false, "FORBIDDEN", "발령 제출 후 장수 소유자가 변경되었습니다.")
        } else when (val applied = executor.issue(queued.requestId,
            DispatchRequest(generalId, queued.targetGeneralId, queued.countyId))) {
            is DispatchExecution.Applied -> result(generalId, "court.dispatch", true)
            is DispatchExecution.Rejected -> result(generalId, "court.dispatch", false, applied.reason.name, applied.reason.message)
        }
        // The issue can update another general; remove only this issuer's queue from its current metadata.
        val current = world.getGeneralById(generalId)!!
        updateMeta(current, current.meta - HwihaQueuedDispatch.META_KEY)
        executions += HwihaCourtExecution(queued.requestId, queued.ownerUserId, result)
    }

    fun expireDue() { executor.expireDue() }
    fun takeExecutions(): List<HwihaCourtExecution> = executions.toList().also { executions.clear() }

    fun rejectPersonalReservation(generalId: Int, inputId: String) =
        HwihaTurnOutcome.Rejected(inputId, "INVALID_INPUT_CHANNEL", "발령과 응답은 개인 행동 예약으로 실행할 수 없습니다.")

    private fun updateMeta(before: TurnGeneral, meta: Map<String, Any?>) {
        val after = before.copy(meta = meta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }
    private fun result(generalId: Int, inputId: String, ok: Boolean, code: String? = null, reason: String? = null,
        type: String = if (ok) "executionApplied" else "executionRejected") =
        CommandLifecycleResult(type = type, ok = ok, commandKind = "COURT_DECISION", actionCode = inputId,
            generalId = generalId, code = code, reason = reason)
}
