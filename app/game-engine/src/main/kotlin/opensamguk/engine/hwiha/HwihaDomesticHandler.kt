package opensamguk.engine.hwiha

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand.HwihaCourtInput
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
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
    fun handle(command: HwihaCourtInput): CommandLifecycleResult {
        val kind = kindOf(command.inputId)
        fun deny(code: String, reason: String) = result(command.generalId, command.inputId, kind, false, code, reason)
        if (world.ruleProfile != RuleProfile.HWIHA) return deny("WRONG_RULE_PROFILE", "이 월드의 규칙에서 사용할 수 없는 입력입니다.")
        if (!command.requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) return deny("INVALID_REQUEST", "입력 식별자가 올바르지 않습니다.")
        val actor = world.getGeneralById(command.generalId) ?: return deny("ACTOR_NOT_FOUND", "장수를 찾을 수 없습니다.")
        if (command.ownerUserId <= 0 || actor.userId?.toLongOrNull() != command.ownerUserId.toLong())
            return deny("FORBIDDEN", "자신의 장수만 조작할 수 있습니다.")
        val state = context.projection(world)
        val now = state.now
        val outcome: DomesticAssessment = when (command.inputId) {
            HwihaDomesticInput.PLACEMENT -> {
                val request = HwihaDomesticInput.parsePlacement(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "배치할 카드와 자리를 확인해 주세요.")
                HwihaDomesticRules.assessPlacement(request, state).also {
                    if (it is DomesticAssessment.Eligible) storePlacement(command.requestId, request, it.person!!.id, now)
                }
            }
            HwihaDomesticInput.POLICY -> {
                val request = HwihaDomesticInput.parsePolicy(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "방침 대상과 방침을 확인해 주세요.")
                HwihaDomesticRules.assessPolicy(request, state).also {
                    if (it is DomesticAssessment.Eligible) storePolicy(command.requestId, request, state, now)
                }
            }
            HwihaDomesticInput.WORK -> {
                val request = HwihaDomesticInput.parseWork(actor.id, command.argJson)
                    ?: return deny("INVALID_REQUEST", "공사할 현과 공사를 확인해 주세요.")
                infrastructureTargetError(request, state)?.let { return deny("INVALID_INFRASTRUCTURE_SITE", it) }
                HwihaDomesticRules.assessWork(request, state).also {
                    if (it is DomesticAssessment.Eligible) storeWork(command.requestId, request, now)
                }
            }
            else -> return deny("UNKNOWN_INPUT", "등록되지 않은 내정 입력입니다.")
        }
        return when (outcome) {
            is DomesticAssessment.Rejected -> deny(outcome.reason.name, outcome.reason.message)
            is DomesticAssessment.Eligible -> result(actor.id, command.inputId, kind, true, type = "reservationAccepted")
        }
    }

    /** 12순 개인 예약으로 들어온 지속 입력은 채널이 틀렸다(슬롯을 쓰지 않는다, §5.1). */
    fun rejectPersonalReservation(inputId: String) =
        HwihaTurnOutcome.Rejected(inputId, "INVALID_INPUT_CHANNEL", "배치·방침·공사는 명령 목록에 넣지 않고 따로 입력합니다.")

    private fun storePlacement(requestId: String, request: PlacementRequest, cardGeneralId: Int, now: HwihaPhase) {
        val card = checkNotNull(world.getGeneralById(cardGeneralId))
        val current = HwihaPlacementState.read(card.meta)
        val order = HwihaPlacementOrder(requestId, request.actorId, request.cardId, request.post, request.target, now)
        val next = HwihaPlacementState(current?.active, order)
        world.updateGeneralMeta(recorder, card, card.meta.withKey(HwihaPlacementState.META_KEY, next.toMetaValue()))
    }

    private fun storePolicy(requestId: String, request: PolicyRequest, state: HwihaDomesticProjection, now: HwihaPhase) {
        val order = HwihaPolicyOrder(request.policy, requestId, request.actorId, now)
        when (val target = request.target) {
            is PolicyTarget.County -> {
                val city = checkNotNull(world.getCityById(target.countyId))
                val current = HwihaCountyPolicyState.read(city.meta)
                val next = HwihaCountyPolicyState(HwihaPolicySlot(current?.slot?.active, order), current?.lastApplied)
                world.updateCityMeta(recorder, city.id, city.meta.withKey(HwihaCountyPolicyState.META_KEY, next.toMetaValue()))
            }
            is PolicyTarget.Commandery -> {
                val nationId = checkNotNull(state.person(request.actorId)).nationId
                val nation = checkNotNull(world.getNationById(nationId))
                val current = HwihaCommanderyPolicies.read(nation.meta) ?: HwihaCommanderyPolicies(emptyList())
                val slot = HwihaPolicySlot(current[target.commanderyId]?.slot?.active, order)
                world.updateNationMeta(recorder, nationId, nation.meta.withKey(HwihaCommanderyPolicies.META_KEY,
                    current.with(target.commanderyId, slot).toMetaValue()))
            }
            is PolicyTarget.Corps -> {
                val owner = checkNotNull(world.getGeneralById(request.actorId))
                val corps = HwihaDomesticRules.deployedCorps(state).single { it.orderId == target.orderId }
                val current = HwihaCorpsPolicies.read(owner.meta) ?: HwihaCorpsPolicies(emptyList())
                val slot = HwihaPolicySlot(current.forOrder(corps.orderId)?.slot?.active, order)
                world.updateGeneralMeta(recorder, owner, owner.meta.withKey(HwihaCorpsPolicies.META_KEY,
                    current.with(corps.orderId, corps.commanderGeneralId, slot).toMetaValue()))
            }
        }
    }

    private fun storeWork(requestId: String, request: WorkRequest, now: HwihaPhase) {
        val city = checkNotNull(world.getCityById(request.countyId))
        val current = HwihaCountyWorks.read(city.meta)
        val next = HwihaCountyWorks(HwihaDomesticEffects.newWork(context.design, request.work, requestId, request.actorId, now,
            request.edgeId, request.row, request.col),
            current?.completed.orEmpty())
        world.updateCityMeta(recorder, city.id, city.meta.withKey(HwihaCountyWorks.META_KEY, next.toMetaValue()))
    }

    private fun infrastructureTargetError(request: WorkRequest, state: HwihaDomesticProjection): String? {
        if (context.roadGates.isEmpty() || request.work !in setOf(DomesticWork.ROAD, DomesticWork.FORTIFICATION)) return null
        val gate = context.roadGates.singleOrNull { it.edgeId == request.edgeId }
            ?: return "지도에 등록된 도로 접경을 골라 주세요."
        val provinceId = state.county(request.countyId)?.provinceId
            ?: return "공사할 현의 지도 구역을 찾을 수 없습니다."
        val topology = context.topology ?: return "도로 위상 자료를 읽을 수 없습니다."
        val edge = topology.traversalEdges.singleOrNull { it.id == gate.edgeId }
            ?: return "도로 접경의 위상 자료를 읽을 수 없습니다."
        if (listOf(edge.from, edge.to).none { it is opensamguk.logic.world.StrategicNodeRef.LandProvince && it.id == provinceId })
            return "해당 현에 닿는 도로만 공사할 수 있습니다."
        val passage = try { HwihaLandPassageState.read(world.getState().meta, topology) }
            catch (_: IllegalArgumentException) { null } ?: return "도로 통행 상태를 읽을 수 없습니다."
        val active = passage.edgeStates[gate.edgeId]?.active ?: return "도로 통행 상태가 비어 있습니다."
        if (request.work == DomesticWork.ROAD) {
            if (!gate.buildable) return "성 자리와 이어지지 않는 접경입니다. 나루나 별도 도하가 필요합니다."
            if (request.row != null || request.col != null) return "도로 개척에는 접경만 지정해 주세요."
            if (active) return "이미 열린 도로입니다."
        } else {
            if (!active) return "보루는 개통된 도로에만 세울 수 있습니다."
            if (request.row == null || request.col == null || gate.fortCells.none {
                    it.provinceId == provinceId && it.row == request.row && it.col == request.col
                }) return "보루는 자기 현의 도로 칸 또는 인접한 마른땅 칸에 세워야 합니다."
            val forts = try { HwihaRoadFortState.read(world.getState().meta) }
                catch (_: IllegalArgumentException) { return "보루 상태를 읽을 수 없습니다." }
            if (forts.any { it.row == request.row && it.col == request.col }) return "이미 보루가 있는 칸입니다."
        }
        return null
    }

    private fun kindOf(inputId: String) = when (inputId) {
        HwihaDomesticInput.PLACEMENT -> "PLACEMENT"
        HwihaDomesticInput.POLICY -> "POLICY"
        HwihaDomesticInput.WORK -> "WORK"
        else -> "COURT_DECISION"
    }

    private fun result(generalId: Int, inputId: String, kind: String, ok: Boolean, code: String? = null, reason: String? = null,
        type: String = if (ok) "executionApplied" else "executionRejected") =
        CommandLifecycleResult(type = type, ok = ok, commandKind = kind, actionCode = inputId, generalId = generalId,
            code = code, reason = reason)
}
