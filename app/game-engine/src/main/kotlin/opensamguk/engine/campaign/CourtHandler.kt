package opensamguk.engine.campaign

import opensamguk.logic.vision.ScoutInputCodec

import opensamguk.logic.domestic.FieldInput

import opensamguk.logic.domestic.DomesticInput

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.InputResolved
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.turn.*
import opensamguk.logic.input.*

/** Deferred results are emitted as sequence 2 in the same flush that consumes the issuer queue. */
data class CourtExecution(val requestId: String, val ownerUserId: Int, val result: CommandLifecycleResult)

class CourtHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val domesticContext: DomesticContext = DomesticContext(),
    private val catalog: InputCatalog = InputCatalog.load(),
) {
    private val executor = DispatchExecutor(world, recorder)
    private val domestic by lazy { DomesticHandler(world, recorder, domesticContext) }
    private val courtAction by lazy { CourtActionExecutor(world, recorder, domesticContext) }
    private val stratagem by lazy { StratagemActionExecutor(world, recorder, domesticContext) }
    private val executions = mutableListOf<CourtExecution>()

    fun handle(command: ImmediateInput): CommandLifecycleResult {
        var outcome: CommandLifecycleResult? = null
        val channelHandlers = mutableMapOf<String, InputHandler>(
            "action.enlist" to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "출사는 개인 행동 예약으로 입력해야 합니다.") },
            DeployInputs.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "본인 출병은 개인 행동 예약으로 입력해야 합니다.") },
            ScoutInputCodec.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "첩보는 개인 행동 예약으로 입력해야 합니다.") },
            SiegeHandler.ASSAULT to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "강공은 개인 행동 예약으로 입력해야 합니다.") },
            SiegeHandler.DEMAND_SURRENDER to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "항복 권고는 개인 행동 예약으로 입력해야 합니다.") },
            RoadFortSiegeInput.INPUT_ID to InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "보루 포위는 개인 행동 예약으로 입력해야 합니다.") },
            "court.dispatch" to InputHandler { outcome = handleKnown(command) },
            "court.dispatchReply" to InputHandler { outcome = handleKnown(command) },
            RewardInput.INPUT_ID to InputHandler { outcome = handleKnown(command) },
            PoliticalConsent.COURT_INPUT_ID to InputHandler { outcome = handleKnown(command) },
            // Standing inputs share this immediate channel: they never occupy a 12-phase slot (§5.1).
            DomesticInput.PLACEMENT to InputHandler { outcome = domestic.handle(command) },
            DomesticInput.POLICY to InputHandler { outcome = domestic.handle(command) },
            DomesticInput.WORK to InputHandler { outcome = domestic.handle(command) },
        )
        if (catalog[DomesticInput.REDUCE]?.deliveryState?.hasHandler == true)
            channelHandlers[DomesticInput.REDUCE] = InputHandler { outcome = domestic.handle(command) }
        for (travelId in TravelInput.INPUT_IDS) {
            channelHandlers[travelId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "직접 이동은 개인 행동 예약으로 입력해야 합니다.") }
        }
        for (legacyId in CourtInput.INPUT_IDS) {
            if (catalog[legacyId]?.deliveryState?.hasHandler == true) {
                channelHandlers[legacyId] = InputHandler { outcome = handleKnown(command) }
            }
        }
        for (stratagemId in StratagemInput.INPUT_IDS) {
            if (catalog[stratagemId]?.deliveryState?.hasHandler == true) {
                channelHandlers[stratagemId] = InputHandler { outcome = handleKnown(command) }
            }
        }
        for (fieldId in FieldInput.INPUT_IDS) {
            channelHandlers[fieldId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                "INVALID_INPUT_CHANNEL", "현장 행동은 개인 행동 예약으로 입력해야 합니다.") }
        }
        for (militaryId in MilitaryInput.INPUT_IDS) {
            if (catalog[militaryId]?.deliveryState?.hasHandler == true) {
                channelHandlers[militaryId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                    "INVALID_INPUT_CHANNEL", "직접 군사 행동은 개인 행동 예약으로 입력해야 합니다.") }
            }
        }
        for (personalId in PersonalInput.FIELD_IDS) {
            if (catalog[personalId]?.deliveryState?.hasHandler == true) {
                channelHandlers[personalId] = InputHandler { outcome = result(command.generalId, command.inputId, false,
                    "INVALID_INPUT_CHANNEL", "개인 현장 행동은 개인 행동 예약으로 입력해야 합니다.") }
            }
        }
        for (peopleId in PeopleInput.INPUT_IDS) {
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
        val registry = InputRegistry(catalog, channelHandlers)
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
                val request = DispatchInput.parse(actor.id, command.argJson) ?: return deny("INVALID_REQUEST", "발령 대상과 목적지를 확인해 주세요.")
                val existing = try { QueuedDispatch.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny("STATE_UNAVAILABLE", "저장된 발령 대기 상태를 확인할 수 없습니다.")
                }
                if (existing != null) return deny("ALREADY_QUEUED", "다음 턴에 실행할 발령이 이미 있습니다.")
                val assessment = executor.assess(request)
                if (assessment is DispatchAssessment.Rejected) return deny(assessment.reason.name, assessment.reason.message)
                val queued = QueuedDispatch(command.requestId, command.ownerUserId, request.targetGeneralId, request.countyId)
                updateMeta(actor, actor.meta + (QueuedDispatch.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            "court.dispatchReply" -> {
                val request = DispatchReplyInput.parse(actor.id, command.argJson) ?: return deny("INVALID_REQUEST", "발령 응답을 확인해 주세요.")
                when (val applied = executor.reply(request)) {
                    is DispatchExecution.Applied -> result(actor.id, command.inputId, true)
                    is DispatchExecution.Rejected -> deny(applied.reason.name, applied.reason.message)
                }
            }
            RewardInput.INPUT_ID -> {
                val request = RewardInput.parse(actor.id, command.argJson) ?: return deny("INVALID_REQUEST", "상사할 카드와 금을 확인해 주세요.")
                val existing = try { QueuedReward.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny("STATE_UNAVAILABLE", "저장된 상사 대기 상태를 확인할 수 없습니다.")
                }
                if (existing != null) return deny("ALREADY_QUEUED", "다음 턴에 실행할 상사가 이미 있습니다.")
                if (world.getRetainerById(request.retainerId)?.takeIf { it.masterGeneralId == actor.id && it.generalId != null } == null)
                    return deny(RewardExecutor.Failure.CARD_UNAVAILABLE.name, RewardExecutor.Failure.CARD_UNAVAILABLE.message)
                val queued = QueuedReward(command.requestId, command.ownerUserId, request.retainerId, request.money)
                updateMeta(actor, actor.meta + (QueuedReward.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            PoliticalConsent.COURT_INPUT_ID -> {
                val consent = PoliticalConsent.parse(actor.id, command.argJson)
                    ?: return deny(PoliticalFailure.INVALID_INPUT.name, PoliticalFailure.INVALID_INPUT.message)
                val state = domesticContext.projection(world)
                PoliticalRules.assessConsent(actor.id, consent, state)?.let {
                    return deny(it.name, it.message)
                }
                updateMeta(actor, actor.meta + (PoliticalConsent.META_KEY to consent.toMetaValue()))
                result(actor.id, command.inputId, true)
            }
            in CourtInput.INPUT_IDS -> {
                val json = CourtInput.canonical(actor.id, command.inputId, command.argJson)
                    ?: return deny(CourtFailure.INVALID_INPUT.name, CourtFailure.INVALID_INPUT.message)
                val existing = try { QueuedCourtAction.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny(CourtFailure.STATE_UNAVAILABLE.name, CourtFailure.STATE_UNAVAILABLE.message)
                }
                if (existing != null) return deny(CourtFailure.ALREADY_QUEUED.name,
                    CourtFailure.ALREADY_QUEUED.message)
                val assessment = courtAction.assess(actor.id, command.inputId, json)
                if (assessment is CourtAssessment.Rejected) return deny(assessment.reason.name, assessment.reason.message)
                val queued = QueuedCourtAction(command.requestId, command.ownerUserId, command.inputId, json)
                updateMeta(actor, actor.meta + (QueuedCourtAction.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            in StratagemInput.INPUT_IDS -> {
                val request = StratagemInput.parse(actor.id, command.inputId, command.argJson)
                    ?: return deny(StratagemFailure.INVALID_INPUT.name, StratagemFailure.INVALID_INPUT.message)
                val existing = try { QueuedStratagemAction.read(actor.meta) } catch (_: IllegalArgumentException) {
                    return deny(StratagemFailure.STATE_UNAVAILABLE.name, StratagemFailure.STATE_UNAVAILABLE.message)
                }
                if (existing != null) return deny(StratagemFailure.ALREADY_QUEUED.name,
                    StratagemFailure.ALREADY_QUEUED.message)
                val assessment = stratagem.assess(request)
                if (assessment is StratagemAssessment.Rejected)
                    return deny(assessment.reason.name, assessment.reason.message)
                val queued = QueuedStratagemAction(command.requestId, command.ownerUserId, command.inputId,
                    StratagemInput.canonicalJson(request))
                updateMeta(actor, actor.meta + (QueuedStratagemAction.META_KEY to queued.toMetaValue()))
                result(actor.id, command.inputId, true, type = "reservationAccepted")
            }
            else -> deny("UNKNOWN_INPUT", "등록되지 않은 조정 입력입니다.")
        }
    }

    /** Runs beside, not instead of, the issuer's personal action. Lifecycle controls phase eligibility. */
    fun onIssuerTurn(generalId: Int) {
        if (world.ruleProfile != RuleProfile.HWIHA) return
        runQueuedReward(generalId)
        runQueuedCourtAction(generalId)
        runQueuedStratagem(generalId)
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { QueuedDispatch.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, QueuedDispatch.META_KEY, "court.dispatch")
            return
        }
        if (queued != null && rejectUndeliveredQueue(actor, QueuedDispatch.META_KEY,
                "court.dispatch", queued.requestId, queued.ownerUserId)) return
        if (queued == null) {
            NpcDispatchSelector.select(world, generalId, executor)?.let { request ->
                // The NPC lord's reason is the target's dispatch record (spec §14: 발령 근거를 「지난 순」에).
                executor.issue(NpcDispatchSelector.dispatchId(world, request), request,
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
        updateMeta(current, current.meta - QueuedDispatch.META_KEY)
        executions += CourtExecution(queued.requestId, queued.ownerUserId, result)
    }

    /** 상사 대기는 발령 대기와 독립이다 — 결정권자의 턴에 한 건 실행하고 결과를 같은 flush 에 싣는다. */
    private fun runQueuedReward(generalId: Int) {
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { QueuedReward.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, QueuedReward.META_KEY, RewardInput.INPUT_ID)
            return
        } ?: return
        if (rejectUndeliveredQueue(actor, QueuedReward.META_KEY, RewardInput.INPUT_ID,
                queued.requestId, queued.ownerUserId)) return
        val result = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, RewardInput.INPUT_ID, false, "FORBIDDEN", "상사 제출 후 장수 소유자가 변경되었습니다.")
        } else when (val failure = RewardExecutor(world, recorder).reward(RewardRequest(generalId, queued.retainerId, queued.money))) {
            null -> result(generalId, RewardInput.INPUT_ID, true)
            else -> result(generalId, RewardInput.INPUT_ID, false, failure.name, failure.message)
        }
        val current = world.getGeneralById(generalId)!!
        updateMeta(current, current.meta - QueuedReward.META_KEY)
        executions += CourtExecution(queued.requestId, queued.ownerUserId, result)
    }

    private fun runQueuedCourtAction(generalId: Int) {
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { QueuedCourtAction.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, QueuedCourtAction.META_KEY, "court.unknown")
            return
        } ?: return
        if (rejectUndeliveredQueue(actor, QueuedCourtAction.META_KEY, queued.inputId,
                queued.requestId, queued.ownerUserId)) return
        val resolved = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, queued.inputId, false, "FORBIDDEN", "제출 후 소유권이 변경되었습니다.")
        } else when (val rejected = courtAction.execute(generalId, queued.inputId, queued.argJson)) {
            null -> result(generalId, queued.inputId, true)
            else -> result(generalId, queued.inputId, false, rejected.reason.name, rejected.reason.message)
        }
        val current = world.getGeneralById(generalId) ?: return
        updateMeta(current, (current.meta - QueuedCourtAction.META_KEY) +
            ("hwihaLegacyCourtLastExecution" to mapOf("requestId" to queued.requestId,
                "inputId" to queued.inputId, "ok" to resolved.ok, "code" to resolved.code)))
        executions += CourtExecution(queued.requestId, queued.ownerUserId, resolved)
    }

    private fun runQueuedStratagem(generalId: Int) {
        val actor = world.getGeneralById(generalId) ?: return
        val queued = try { QueuedStratagemAction.read(actor.meta) } catch (_: IllegalArgumentException) {
            discardMalformedQueue(actor, QueuedStratagemAction.META_KEY, "stratagem.unknown")
            return
        } ?: return
        if (rejectUndeliveredQueue(actor, QueuedStratagemAction.META_KEY, queued.inputId,
                queued.requestId, queued.ownerUserId)) return
        val request = StratagemInput.parse(generalId, queued.inputId, queued.argJson)
        val resolved = if (actor.userId?.toLongOrNull() != queued.ownerUserId.toLong()) {
            result(generalId, queued.inputId, false, "FORBIDDEN", "제출 후 소유권이 변경되었습니다.")
        } else if (request == null) {
            result(generalId, queued.inputId, false, StratagemFailure.INVALID_INPUT.name,
                StratagemFailure.INVALID_INPUT.message)
        } else when (val rejected = stratagem.execute(request)) {
            null -> result(generalId, queued.inputId, true)
            else -> result(generalId, queued.inputId, false, rejected.reason.name, rejected.reason.message)
        }
        val current = world.getGeneralById(generalId) ?: return
        updateMeta(current, (current.meta - QueuedStratagemAction.META_KEY) +
            ("hwihaLegacyStratagemLastExecution" to mapOf("requestId" to queued.requestId,
                "inputId" to queued.inputId, "ok" to resolved.ok, "code" to resolved.code)))
        executions += CourtExecution(queued.requestId, queued.ownerUserId, resolved)
    }

    fun expireDue() { executor.expireDue() }

    private fun rejectUndeliveredQueue(actor: TurnGeneral, key: String, inputId: String,
        requestId: String, ownerUserId: Int): Boolean {
        val rejection = catalog.rejectionFor(world.ruleProfile, inputId) ?: return false
        val current = world.getGeneralById(actor.id) ?: return true
        updateMeta(current, current.meta - key)
        Records.general(world, actor.id, RecordKind.INPUT_REJECTED, rejection.message,
            linkedMapOf("inputId" to inputId, "code" to rejection.name))
        executions += CourtExecution(requestId, ownerUserId,
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
        Records.general(world, actor.id, RecordKind.INPUT_REJECTED, reason,
            linkedMapOf("inputId" to inputId, "code" to "STATE_UNAVAILABLE"))
        if (requestId != null && ownerUserId != null) {
            executions += CourtExecution(requestId, ownerUserId,
                result(actor.id, inputId, false, "STATE_UNAVAILABLE", reason))
        }
    }

    companion object {
        const val NPC_DISPATCH_REASON = "담당 장수가 없는 아군 현의 첫 부임 대상으로 발령되었습니다."
    }
    fun takeExecutions(): List<CourtExecution> = executions.toList().also { executions.clear() }

    fun rejectPersonalReservation(generalId: Int, inputId: String) =
        TurnOutcome.Rejected(inputId, "INVALID_INPUT_CHANNEL", "이 입력은 별도 조정·계책 채널에서 제출해야 합니다.")

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
