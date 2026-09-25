package opensamguk.engine.hwiha

import opensamguk.logic.domestic.PlacementOrder
import opensamguk.logic.domestic.PlacementState
import opensamguk.logic.domestic.PolicyOrder
import opensamguk.logic.domestic.PolicySlot
import opensamguk.logic.domestic.CountyPolicyState
import opensamguk.logic.domestic.CommanderyPolicies
import opensamguk.logic.domestic.CorpsPolicyAssignments
import opensamguk.logic.domestic.CountyWorks

import opensamguk.logic.domestic.DomesticWork
import opensamguk.logic.domestic.PolicyTarget
import opensamguk.logic.domestic.PlacementRequest
import opensamguk.logic.domestic.PolicyRequest
import opensamguk.logic.domestic.WorkRequest
import opensamguk.logic.domestic.DomesticInput
import opensamguk.logic.domestic.DomesticEffects

import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticAssessment
import opensamguk.logic.domestic.DomesticRules

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.InputResolved
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.*

/**
 * 배치·방침·공사 접수(지속 입력). 명령 목록 12순 슬롯을 쓰지 않고(§5.1), 조정 입력과 같은 즉시 인테이크 봉투
 * (`hwihaCourtInput`)로 들어온다. 접수는 현재 상태로 재검사한 뒤 **대기**로만 저장한다 — 배치·방침은 해당 카드의 다음 턴에,
 * 공사는 다음 순 경계부터 효력이 생긴다(§4). 결과 봉투의 commandKind 는 PLACEMENT·POLICY·WORK 이고 type 은
 * `reservationAccepted`(대기 저장)다. 효력 시점의 거절은 그 상태 기록과 장수 로그에 남는다.
 */
class HwihaDomesticHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val context: HwihaDomesticContext,
) {
    fun handle(command: ImmediateInput): CommandLifecycleResult {
        val kind = kindOf(command.inputId)
        fun deny(code: String, reason: String) = result(command.generalId, command.inputId, kind, false, code, reason)
        if (world.ruleProfile != RuleProfile.HWIHA) return deny("WRONG_RULE_PROFILE", "이 월드의 규칙에서 사용할 수 없는 입력입니다.")
        if (!command.requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return deny("INVALID_REQUEST", "입력 식별자가 올바르지 않습니다.")
        val actor = world.getGeneralById(command.generalId) ?: return deny("ACTOR_NOT_FOUND", "장수를 찾을 수 없습니다.")
        if (command.ownerUserId <= 0 || actor.userId?.toLongOrNull() != command.ownerUserId.toLong())
            return deny("FORBIDDEN", "자신의 장수만 조작할 수 있습니다.")
        if (command.inputId == DomesticInput.REDUCE &&
            HwihaInputCatalog.load()[command.inputId]?.deliveryState?.hasHandler != true)
            return deny(InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = context.projection(world)
        val now = state.now
        val outcome: DomesticAssessment = when (command.inputId) {
            DomesticInput.PLACEMENT -> {
                val request = DomesticInput.parsePlacement(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "배치할 카드와 자리를 확인해 주세요.")
                DomesticRules.assessPlacement(request, state).also {
                    if (it is DomesticAssessment.Eligible) storePlacement(command.requestId, request, it.person!!.id, now)
                }
            }
            DomesticInput.POLICY -> {
                val request = DomesticInput.parsePolicy(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "방침 대상과 방침을 확인해 주세요.")
                DomesticRules.assessPolicy(request, state).also {
                    if (it is DomesticAssessment.Eligible) storePolicy(command.requestId, request, state, now)
                }
            }
            DomesticInput.WORK -> {
                val request = DomesticInput.parseWork(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "공사할 현과 공사를 확인해 주세요.")
                infrastructureTargetError(request, state)?.let { return deny("INVALID_INFRASTRUCTURE_SITE", it) }
                DomesticRules.assessWork(request, state).also {
                    if (it is DomesticAssessment.Eligible) storeWork(command.requestId, request, now)
                }
            }
            DomesticInput.REDUCE -> {
                val request = DomesticInput.parseWork(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "감축할 현을 확인해 주세요.")
                DomesticRules.assessReduce(request, state).also {
                    if (it is DomesticAssessment.Eligible) reduceFortification(request.countyId)
                }
            }
            else -> return deny("UNKNOWN_INPUT", "등록되지 않은 내정 입력입니다.")
        }
        return when (outcome) {
            is DomesticAssessment.Rejected -> deny(outcome.reason.name, outcome.reason.message)
            is DomesticAssessment.Eligible -> result(actor.id, command.inputId, kind, true,
                type = if (command.inputId == DomesticInput.REDUCE) "executionApplied" else "reservationAccepted")
        }
    }

    /** 12순 개인 예약으로 들어온 지속 입력은 채널이 틀렸다(슬롯을 쓰지 않는다, §5.1). */
    fun rejectPersonalReservation(inputId: String) =
        HwihaTurnOutcome.Rejected(inputId, "INVALID_INPUT_CHANNEL", "배치·방침·공사는 명령 목록에 넣지 않고 따로 입력합니다.")

    private fun storePlacement(requestId: String, request: PlacementRequest, cardGeneralId: Int, now: HwihaPhase) {
        val card = checkNotNull(world.getGeneralById(cardGeneralId))
        val current = PlacementState.read(card.meta)
        val order = PlacementOrder(requestId, request.actorId, request.cardId, request.post, request.target, now)
        val next = PlacementState(current?.active, order)
        world.updateGeneralMeta(recorder, card, card.meta.withKey(PlacementState.META_KEY, next.toMetaValue()))
    }

    private fun storePolicy(requestId: String, request: PolicyRequest, state: DomesticProjection, now: HwihaPhase) {
        val order = PolicyOrder(request.policy, requestId, request.actorId, now)
        when (val target = request.target) {
            is PolicyTarget.County -> {
                val city = checkNotNull(world.getCityById(target.countyId))
                val current = CountyPolicyState.read(city.meta)
                val next = CountyPolicyState(PolicySlot(current?.slot?.active, order), current?.lastApplied)
                world.updateCityMeta(recorder, city.id, city.meta.withKey(CountyPolicyState.META_KEY, next.toMetaValue()))
            }
            is PolicyTarget.Commandery -> {
                val nationId = checkNotNull(state.person(request.actorId)).nationId
                val nation = checkNotNull(world.getNationById(nationId))
                val current = CommanderyPolicies.read(nation.meta) ?: CommanderyPolicies(emptyList())
                val slot = PolicySlot(current[target.commanderyId]?.slot?.active, order)
                world.updateNationMeta(recorder, nationId, nation.meta.withKey(CommanderyPolicies.META_KEY,
                    current.with(target.commanderyId, slot).toMetaValue()))
            }
            is PolicyTarget.Corps -> {
                val owner = checkNotNull(world.getGeneralById(request.actorId))
                val corps = DomesticRules.deployedCorps(state).single { it.orderId == target.orderId }
                val current = CorpsPolicyAssignments.read(owner.meta) ?: CorpsPolicyAssignments(emptyList())
                val slot = PolicySlot(current.forOrder(corps.orderId)?.slot?.active, order)
                world.updateGeneralMeta(recorder, owner, owner.meta.withKey(CorpsPolicyAssignments.META_KEY,
                    current.with(corps.orderId, corps.commanderGeneralId, slot).toMetaValue()))
            }
        }
    }

    private fun storeWork(requestId: String, request: WorkRequest, now: HwihaPhase) {
        val city = checkNotNull(world.getCityById(request.countyId))
        val current = CountyWorks.read(city.meta)
        val next = CountyWorks(DomesticEffects.newWork(context.design, request.work, requestId, request.actorId, now,
            request.edgeId, request.row, request.col),
            current?.completed.orEmpty())
        world.updateCityMeta(recorder, city.id, city.meta.withKey(CountyWorks.META_KEY, next.toMetaValue()))
    }

    private fun infrastructureTargetError(request: WorkRequest, state: DomesticProjection): String? {
        val passage = try { context.topology?.let { LandPassageState.read(world.getState().meta, it) } }
            catch (_: IllegalArgumentException) { null }
        val forts = try { RoadFortState.read(world.getState().meta) }
            catch (_: IllegalArgumentException) { return "보루 상태를 읽을 수 없습니다." }
        return InfrastructureSiteRules.error(request, state,
            InfrastructureSiteState(context.topology, context.roadGates, passage, forts))
    }

    private fun reduceFortification(countyId: Int) {
        val city = checkNotNull(world.getCityById(countyId))
        val works = checkNotNull(CountyWorks.read(city.meta))
        val remaining = CountyWorks(null, works.completed.filterNot {
            it.work == DomesticWork.FORTIFICATION && it.edgeId == null
        })
        val next = city.copy(defence = (city.defence - 500).coerceAtLeast(0),
            wall = (city.wall - 500).coerceAtLeast(0),
            meta = city.meta.withKey(CountyWorks.META_KEY, remaining.toMetaValue()))
        recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(next))
        world.applyCityDirtyFree(next)
    }

    private fun kindOf(inputId: String) = when (inputId) {
        DomesticInput.PLACEMENT -> "PLACEMENT"
        DomesticInput.POLICY -> "POLICY"
        DomesticInput.WORK -> "WORK"
        DomesticInput.REDUCE -> "WORK"
        else -> "COURT_DECISION"
    }

    private fun result(generalId: Int, inputId: String, kind: String, ok: Boolean, code: String? = null, reason: String? = null,
        type: String = if (ok) "executionApplied" else "executionRejected") =
        CommandLifecycleResult(type = type, ok = ok, commandKind = kind, actionCode = inputId, generalId = generalId,
            code = code, reason = reason,
            inputResolved = HwihaInputCatalog.load()[inputId]?.let { InputResolved(inputId, it.kind.name, ok, reason) })
}
