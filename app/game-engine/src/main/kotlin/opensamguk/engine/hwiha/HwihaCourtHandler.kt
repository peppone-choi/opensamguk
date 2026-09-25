package opensamguk.engine.hwiha

import opensamguk.logic.vision.ScoutInputCodec

import opensamguk.logic.domestic.FieldInput

import opensamguk.logic.domestic.DomesticInput

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.InputResolved
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** Deferred results are emitted as sequence 2 in the same flush that consumes the issuer queue. */
data class HwihaCourtExecution(val requestId: String, val ownerUserId: Int, val result: CommandLifecycleResult)

class HwihaCourtHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val domesticContext: HwihaDomesticContext = HwihaDomesticContext(),
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load(),
) {
    private val executor = HwihaDispatchExecutor(world, recorder)
    private val domestic by lazy { HwihaDomesticHandler(world, recorder, domesticContext) }
    private val legacy by lazy { HwihaLegacyCourtExecutor(world, recorder, domesticContext) }
    private val stratagem by lazy { HwihaLegacyStratagemExecutor(world, recorder, domesticContext) }
    private val executions = mutableListOf<HwihaCourtExecution>()

    fun handle(command: ImmediateInput): CommandLifecycleResult {
        var outcome: CommandLifecycleResult? = null
        val channelHandlers = mutableMapOf<String, InputHandler>(
            "action.enlist" to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "출사는 개인 행동 예약으로 입력해야 합니다.") },
            HwihaDeployInput.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "본인 출병은 개인 행동 예약으로 입력해야 합니다.") },
            ScoutInputCodec.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "첩보는 개인 행동 예약으로 입력해야 합니다.") },
            HwihaSiegeHandler.ASSAULT to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "강공은 개인 행동 예약으로 입력해야 합니다.") },
            HwihaSiegeHandler.DEMAND_SURRENDER to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "항복 권고는 개인 행동 예약으로 입력해야 합니다.") },
            RoadFortSiegeInput.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "보루 포위는 개인 행동 예약으로 입력해야 합니다.") },
            "court.dispatch" to InputHandler { outcome = handleKnown(command) },
            "court.dispatchReply" to InputHandler { outcome = handleKnown(command) },
            HwihaRewardInput.INPUT_ID to InputHandler { outcome = handleKnown(command) },
            HwihaPoliticalConsent.COURT_INPUT_ID to InputHandler { outcome = handleKnown(command) },
            // Standing inputs share this immediate channel: they never occupy a 12-phase slot (§5.1).
            DomesticInput.PLACEMENT to InputHandler { outcome = domestic.handle(command) },
            DomesticInput.POLICY to InputHandler { outcome = domestic.handle(command) },
            DomesticInput.WORK to InputHandler { outcome = domestic.handle(command) },
        )
        if (catalog[DomesticInput.REDUCE]?.deliveryState?.hasHandler == true)
            channelHandlers[DomesticInput.REDUCE] = InputHandler { outcome = domestic.handle(command) }
        for (travelId in HwihaTravelInput.INPUT_IDS) {
            channelHandlers[travelId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "직접 이동은 개인 행동 예약으로 입력해야 합니다.") }
        }
        for (legacyId in HwihaLegacyCourtInput.INPUT_IDS) {
            if (catalog[legacyId]?.deliveryState?.hasHandler == true) {
                channelHandlers[legacyId] = InputHandler { outcome = handleKnown(command) }
            }
        }
        for (stratagemId in HwihaLegacyStratagemInput.INPUT_IDS) {
            if (catalog[stratagemId]?.deliveryState?.hasHandler == true) {
                channelHandlers[stratagemId] = InputHandler { outcome = handleKnown(command) }
            }
        }
        for (fieldId in FieldInput.INPUT_IDS) {
            channelHandlers[fieldId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "현장 행동은 개인 행동 예약으로 입력해야 합니다.") }
        }
        for (militaryId in HwihaMilitaryInput.INPUT_IDS) {
            if (catalog[militaryId]?.deliveryState?.hasHandler == true) {
                channelHandlers[militaryId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                    "INVALID_INPUT_CHANNEL", "직접 군사 행동은 개인 행동 예약으로 입력해야 합니다.") }
            }
        }
        for (personalId in HwihaPersonalInput.FIELD_IDS) {
            if (catalog[personalId]?.deliveryState?.hasHandler == true) {
                channelHandlers[personalId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                    "INVALID_INPUT_CHANNEL", "개인 현장 행동은 개인 행동 예약으로 입력해야 합니다.") }
            }
        }
        for (peopleId in HwihaPeopleInput.INPUT_IDS) {
            if (catalog[peopleId]?.deliveryState?.hasHandler == true) {
                channelHandlers[peopleId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                    "INVALID_INPUT_CHANNEL", "인물 직접 행동은 개인 행동 예약으로 입력해야 합니다.") }
            }
        }
        // 이 즉시 입력 채널에도 배달된 직접 행동의 명시적 오채널 응답이 있어야 원장/핸들러
        // 전수 검사에 걸리지 않는다. 새 직접 행동이 추가될 때 이 지도가 누락되지 않게 한다.
        for (entry in catalog.entries.filter { it.kind == InputKind.GENERAL_ACTION && it.deliveryState.hasHandler }) {
            channelHandlers.putIfAbsent(entry.inputId, InputHandler {
                outcome = result(command.generalId, command.inputId, false,
                    "INVALID_INPUT_CHANNEL", "직접 행동은 개인 행동 예약으로 입력해야 합니다.")
            })
        }
        val registry = HwihaInputRegistry(catalog, channelHandlers)
        return when (val resolution = registry.resolve(world.ruleProfile, command.inputId)) {
            is InputResolution.Rejected -> result(command.generalId, command.inputId, false,
                resolution.reason.name, resolution.reason.message)
            is InputResolution.Resolved -> { resolution.handler.handle(); checkNotNull(outcome) }
        }
    }

    private fun handleKnown(command: ImmediateInput): CommandLifecycleResult {
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
            HwihaRewardInput.INPUT_ID -> {
                val request = HwihaRewardInput.parse(actor.id, command.argJson) ?: return deny("INVALID_REQUEST", "상사할 카드와 금을 확인해 주세요.")
                val existing = try { HwihaQueuedReward.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny("STATE_UNAVAILABLE", "저장된 상사 대기 상태를 확인할 수 없습니다.")
                }
                if (existing != null) return deny("ALREADY_QUEUED", "다음 턴에 실행할 상사가 이미 있습니다.")
                if (world.getRetainerById(request.retainerId)?.takeIf { it.masterGeneralId == actor.id && it.generalId != null } == null)
                    return deny(HwihaRewardExecutor.Failure.CARD_UNAVAILABLE.name, HwihaRewardExecutor.Failure.CARD_UNAVAILABLE.message)
                val queued = HwihaQueuedReward(command.requestId, command.ownerUserId, request.retainerId, request.money)
                updateMeta(actor, actor.meta + (HwihaQueuedReward.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            HwihaPoliticalConsent.COURT_INPUT_ID -> {
                val consent = HwihaPoliticalConsent.parse(actor.id, command.argJson)
                    ?: return deny(HwihaPoliticalFailure.INVALID_INPUT.name, HwihaPoliticalFailure.INVALID_INPUT.message)
                val state = domesticContext.projection(world)
                HwihaPoliticalRules.assessConsent(actor.id, consent, state)?.let {
                    return deny(it.name, it.message)
                }
                updateMeta(actor, actor.meta + (HwihaPoliticalConsent.META_KEY to consent.toMetaValue()))
                result(actor.id, command.inputId, true)
            }
            in HwihaLegacyCourtInput.INPUT_IDS -> {
                val json = HwihaLegacyCourtInput.canonical(actor.id, command.inputId, command.argJson)
                    ?: return deny(HwihaLegacyCourtFailure.INVALID_INPUT.name, HwihaLegacyCourtFailure.INVALID_INPUT.message)
                val existing = try { HwihaQueuedLegacyCourt.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny(HwihaLegacyCourtFailure.STATE_UNAVAILABLE.name, HwihaLegacyCourtFailure.STATE_UNAVAILABLE.message)
                }
                if (existing != null) return deny(HwihaLegacyCourtFailure.ALREADY_QUEUED.name,
                    HwihaLegacyCourtFailure.ALREADY_QUEUED.message)
                val assessment = legacy.assess(actor.id, command.inputId, json)
                if (assessment is HwihaLegacyCourtAssessment.Rejected) return deny(assessment.reason.name, assessment.reason.message)
                val queued = HwihaQueuedLegacyCourt(command.requestId, command.ownerUserId, command.inputId, json)
                updateMeta(actor, actor.meta + (HwihaQueuedLegacyCourt.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            in HwihaLegacyStratagemInput.INPUT_IDS -> {
                val request = HwihaLegacyStratagemInput.parse(actor.id, command.inputId, command.argJson)
                    ?: return deny(HwihaLegacyStratagemFailure.INVALID_INPUT.name, HwihaLegacyStratagemFailure.INVALID_INPUT.message)
                val existing = try { HwihaQueuedLegacyStratagem.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny(HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.name, HwihaLegacyStratagemFailure.STATE_UNAVAILABLE.message)
                }
                if (existing != null) return deny(HwihaLegacyStratagemFailure.ALREADY_QUEUED.name,
                    HwihaLegacyStratagemFailure.ALREADY_QUEUED.message)
                val assessment = stratagem.assess(request)
                if (assessment is HwihaLegacyStratagemAssessment.Rejected)
                    return deny(assessment.reason.name, assessment.reason.message)
                val queued = HwihaQueuedLegacyStratagem(command.requestId, command.ownerUserId, command.inputId,
                    HwihaLegacyStratagemInput.canonicalJson(request))
                updateMeta(actor, actor.meta + (HwihaQueuedLegacyStratagem.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            else -> deny("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
    }

    /** Runs beside, not instead of, the issuer's personal action. Lifecycle controls phase eligibility. */
    fun onIssuerTurn(generalId: Int) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        runQueuedReward(generalId)
        runQueuedLegacy(generalId)
        runQueuedStratagem(generalId)
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { HwihaQueuedDispatch.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, HwihaQueuedDispatch.META_KEY, "court.dispatch")
            return
        }
        if (queued != null && rejectUndeliveredQueue(actor, HwihaQueuedDispatch.META_KEY,
                "court.dispatch", queued.requestId, queued.ownerUserId)) return
        if (queued == null) {
            HwihaNpcDispatchSelector.select(world, generalId, executor)?.let { request ->
                // The NPC lord's reason is the target's dispatch record (spec §14: 발령 근거를 「지난 순」에).
                executor.issue(HwihaNpcDispatchSelector.dispatchId(world, request), request,
                    targetText = NPC_DISPATCH_REASON)
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

    /** 상사 대기는 발령 대기와 독립이다 — 결정권자의 턴에 한 건 실행하고 결과를 같은 flush 에 싣는다. */
    private fun runQueuedReward(generalId: Int) {
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { HwihaQueuedReward.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, HwihaQueuedReward.META_KEY, HwihaRewardInput.INPUT_ID)
            return
        } ?: return
        if (rejectUndeliveredQueue(actor, HwihaQueuedReward.META_KEY, HwihaRewardInput.INPUT_ID,
                queued.requestId, queued.ownerUserId)) return
        val result = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, HwihaRewardInput.INPUT_ID, false, "FORBIDDEN", "상사 제출 후 장수 소유자가 변경되었습니다.")
        } else when (val failure = HwihaRewardExecutor(world, recorder).reward(RewardRequest(generalId, queued.retainerId, queued.money))) {
            null -> result(generalId, HwihaRewardInput.INPUT_ID, true)
            else -> result(generalId, HwihaRewardInput.INPUT_ID, false, failure.name, failure.message)
        }
        val current = world.getGeneralById(generalId)!!
        updateMeta(current, current.meta - HwihaQueuedReward.META_KEY)
        executions += HwihaCourtExecution(queued.requestId, queued.ownerUserId, result)
    }

    private fun runQueuedLegacy(generalId: Int) {
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { HwihaQueuedLegacyCourt.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, HwihaQueuedLegacyCourt.META_KEY, "court.unknown")
            return
        } ?: return
        if (rejectUndeliveredQueue(actor, HwihaQueuedLegacyCourt.META_KEY, queued.inputId,
                queued.requestId, queued.ownerUserId)) return
        val resolved = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, queued.inputId, false, "FORBIDDEN", "제출 후 소유권이 변경되었습니다.")
        } else when (val rejected = legacy.execute(generalId, queued.inputId, queued.argJson)) {
            null -> result(generalId, queued.inputId, true)
            else -> result(generalId, queued.inputId, false, rejected.reason.name, rejected.reason.message)
        }
        val current = world.getGeneralById(generalId) ?: return
        updateMeta(current, (current.meta - HwihaQueuedLegacyCourt.META_KEY) +
            ("hwihaLegacyCourtLastExecution" to mapOf("requestId" to queued.requestId,
                "inputId" to queued.inputId, "ok" to resolved.ok, "code" to resolved.code)))
        executions += HwihaCourtExecution(queued.requestId, queued.ownerUserId, resolved)
    }

    private fun runQueuedStratagem(generalId: Int) {
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { HwihaQueuedLegacyStratagem.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, HwihaQueuedLegacyStratagem.META_KEY, "stratagem.unknown")
            return
        } ?: return
        if (rejectUndeliveredQueue(actor, HwihaQueuedLegacyStratagem.META_KEY, queued.inputId,
                queued.requestId, queued.ownerUserId)) return
        val request = HwihaLegacyStratagemInput.parse(generalId, queued.inputId, queued.argJson)
        val resolved = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, queued.inputId, false, "FORBIDDEN", "제출 후 소유권이 변경되었습니다.")
        } else if (request == null) {
            result(generalId, queued.inputId, false, HwihaLegacyStratagemFailure.INVALID_INPUT.name,
                HwihaLegacyStratagemFailure.INVALID_INPUT.message)
        } else when (val rejected = stratagem.execute(request)) {
            null -> result(generalId, queued.inputId, true)
            else -> result(generalId, queued.inputId, false, rejected.reason.name, rejected.reason.message)
        }
        val current = world.getGeneralById(generalId) ?: return
        updateMeta(current, (current.meta - HwihaQueuedLegacyStratagem.META_KEY) +
            ("hwihaLegacyStratagemLastExecution" to mapOf("requestId" to queued.requestId,
                "inputId" to queued.inputId, "ok" to resolved.ok, "code" to resolved.code)))
        executions += HwihaCourtExecution(queued.requestId, queued.ownerUserId, resolved)
    }

    fun expireDue() { executor.expireDue() }

    private fun rejectUndeliveredQueue(actor: TurnGeneral, key: String, inputId: String,
        requestId: String, ownerUserId: Int): Boolean {
        val rejection = catalog.rejectionFor(world.ruleProfile, inputId) ?: return false
        val current = world.getGeneralById(actor.id) ?: return true
        updateMeta(current, current.meta - key)
        HwihaRecords.general(world, actor.id, RecordKind.INPUT_REJECTED, rejection.message,
            linkedMapOf("inputId" to inputId, "code" to rejection.name))
        executions += HwihaCourtExecution(requestId, ownerUserId,
            result(actor.id, inputId, false, rejection.name, rejection.message))
        return true
    }

    private fun discardMalformedQueue(actor: TurnGeneral, key: String, fallbackInputId: String) {
        val raw = actor.meta[key] as? Map<*, *>
        val inputId = (raw?.get("inputId") as? String)?.takeIf { it.isNotBlank() } ?: fallbackInputId
        val requestId = (raw?.get("requestId") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
        val ownerUserId = (raw?.get("ownerUserId") as? Int)?.takeIf { it > 0 }
        val reason = "저장된 대기 입력을 확인할 수 없습니다."
        updateMeta(actor, actor.meta - key)
        HwihaRecords.general(world, actor.id, RecordKind.INPUT_REJECTED, reason,
            linkedMapOf("inputId" to inputId, "code" to "STATE_UNAVAILABLE"))
        if (requestId != null && ownerUserId != null) {
            executions += HwihaCourtExecution(requestId, ownerUserId,
                result(actor.id, inputId, false, "STATE_UNAVAILABLE", reason))
        }
    }

    companion object {
        const val NPC_DISPATCH_REASON = "담당 장수가 없는 아군 현의 첫 부임 대상으로 발령되었습니다."
    }
    fun takeExecutions(): List<HwihaCourtExecution> = executions.toList().also { executions.clear() }

    fun rejectPersonalReservation(generalId: Int, inputId: String) =
        HwihaTurnOutcome.Rejected(inputId, "INVALID_INPUT_CHANNEL", "이 입력은 별도 조정·계책 채널에서 제출해야 합니다.")

    private fun updateMeta(before: TurnGeneral, meta: Map<String, Any?>) {
        val after = before.copy(meta = meta)
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }
    private fun result(generalId: Int, inputId: String, ok: Boolean, code: String? = null, reason: String? = null,
        type: String = if (ok) "executionApplied" else "executionRejected") =
        CommandLifecycleResult(type = type, ok = ok,
            commandKind = if (inputId.startsWith("stratagem.")) "STRATAGEM" else "COURT_DECISION", actionCode = inputId,
            generalId = generalId, code = code, reason = reason,
            inputResolved = catalog[inputId]?.let { InputResolved(inputId, it.kind.name, ok, reason) })
}
