package opensamguk.logic.input

import opensamguk.logic.domestic.PlacementState

data class DispatchPerson(val id: Int, val nationId: Int, val isLord: Boolean, val isHuman: Boolean,
    val meta: Map<String, Any?>, val isNpc: Boolean = false)
enum class DispatchTargetPolicy { HUMAN_ONLY, NPC_AUTOMATED }
data class DispatchRetainer(val id: Int, val masterId: Int, val generalId: Int, val loyalty: Int)
data class DispatchCounty(val id: Int, val nationId: Int)
/** Counties must come from validated administrative classification, not every map city. */
data class DispatchProjection(val profile: RuleProfile, val people: List<DispatchPerson>,
    val retainers: List<DispatchRetainer>, val counties: List<DispatchCounty>)

enum class DispatchFailure(val message: String) {
    WRONG_RULE_PROFILE("이 월드의 규칙에서 사용할 수 없는 입력입니다."),
    ACTOR_NOT_FOUND("장수를 찾을 수 없습니다."), NOT_LORD("주공만 발령할 수 있습니다."),
    TARGET_NOT_FOUND("발령 대상 장수를 찾을 수 없습니다."), TARGET_NOT_HUMAN("사람이 조작하는 장수에게만 발령할 수 있습니다."),
    NPC_ISSUER_REQUIRED("NPC 자동 발령은 NPC 주공만 실행할 수 있습니다."),
    NOT_DIRECT_RETAINER("직접 거느린 장수에게만 발령할 수 있습니다."), DIFFERENT_NATION("같은 세력의 장수에게만 발령할 수 있습니다."),
    INVALID_COUNTY("발령할 수 있는 아군 현을 선택해 주세요."), COUNTY_OCCUPIED("이미 담당 장수나 대기 중인 발령이 있는 현입니다."),
    ALREADY_PENDING("대상 장수가 이전 발령에 아직 응답하지 않았습니다."), NO_DISPATCH("응답할 발령이 없습니다."),
    ALREADY_QUEUED("다음 턴에 실행할 발령이 이미 있습니다."), ALREADY_RESOLVED("이미 처리된 발령입니다."),
    NOT_RECIPIENT("자신에게 도착한 발령에만 응답할 수 있습니다."), RELATION_CHANGED("발령 이후 소속이나 휘하 관계가 바뀌었습니다."),
    POLICY_UNAVAILABLE("발령 정책을 확인할 수 없습니다."), STATE_UNAVAILABLE("저장된 발령 상태를 확인할 수 없습니다."),
}

sealed interface DispatchAssessment {
    data class Eligible(val issuer: DispatchPerson, val target: DispatchPerson, val card: DispatchRetainer) : DispatchAssessment
    data class Rejected(val reason: DispatchFailure) : DispatchAssessment
}

/** Shared admission/execution checks. No RNG, mutation, persistence or clock access. */
object DispatchRules {
    fun assess(request: DispatchRequest, state: DispatchProjection,
        targetPolicy: DispatchTargetPolicy = DispatchTargetPolicy.HUMAN_ONLY): DispatchAssessment {
        val relation = relationship(request.actorId, request.targetGeneralId, request.countyId, state, targetPolicy)
        if (relation !is DispatchAssessment.Eligible) return relation
        return try {
            val pending = DispatchState.read(relation.target.meta)
            when {
                pending?.status == DispatchStatus.PENDING -> reject(DispatchFailure.ALREADY_PENDING)
                !countyAvailable(request.countyId, request.targetGeneralId, state) -> reject(DispatchFailure.COUNTY_OCCUPIED)
                else -> relation
            }
        } catch (_: IllegalArgumentException) { reject(DispatchFailure.STATE_UNAVAILABLE) }
    }

    /** Continuing an accepted assignment does not depend on a newer pending dispatch. */
    fun assessAssignment(actorId: Int, assignment: CountyAssignment, state: DispatchProjection,
        targetPolicy: DispatchTargetPolicy = DispatchTargetPolicy.HUMAN_ONLY): DispatchAssessment {
        val relation = relationship(assignment.issuerId, actorId, assignment.countyId, state, targetPolicy)
        if (relation !is DispatchAssessment.Eligible) return relation
        if (relation.target.nationId != assignment.nationId) return reject(DispatchFailure.RELATION_CHANGED)
        return try {
            if (!countyAvailable(assignment.countyId, actorId, state)) reject(DispatchFailure.COUNTY_OCCUPIED) else relation
        } catch (_: IllegalArgumentException) { reject(DispatchFailure.STATE_UNAVAILABLE) }
    }

    /** Shared cost precheck: a refusal at the deadline is already an acceptance. */
    fun assessReply(request: DispatchReplyRequest, now: Phase, state: DispatchProjection,
        targetPolicy: DispatchTargetPolicy = DispatchTargetPolicy.HUMAN_ONLY): DispatchAssessment {
        val authority = assessReply(request.actorId, request.dispatchId, state, targetPolicy)
        if (authority !is DispatchAssessment.Eligible) return authority
        val dispatch = DispatchState.read(authority.target.meta)!!
        if (!request.accept && now < dispatch.dueAt) {
            val policy = try { PersonPolicyState.read(authority.target.meta) }
                catch (_: IllegalArgumentException) { null }
            if (policy == null) return reject(DispatchFailure.POLICY_UNAVAILABLE)
        }
        return authority
    }

    /** Even automatic acceptance must revalidate the original relationship and county. */
    fun assessReply(actorId: Int, dispatchId: String, state: DispatchProjection,
        targetPolicy: DispatchTargetPolicy = DispatchTargetPolicy.HUMAN_ONLY): DispatchAssessment {
        if (state.profile != RuleProfile.HWIHA) return reject(DispatchFailure.WRONG_RULE_PROFILE)
        val actor = state.people.singleOrNull { it.id == actorId } ?: return reject(DispatchFailure.ACTOR_NOT_FOUND)
        return try {
            val dispatch = DispatchState.read(actor.meta) ?: return reject(DispatchFailure.NO_DISPATCH)
            if (dispatch.dispatchId != dispatchId || dispatch.targetId != actorId) return reject(DispatchFailure.NOT_RECIPIENT)
            if (dispatch.status != DispatchStatus.PENDING) return reject(DispatchFailure.ALREADY_RESOLVED)
            val relation = relationship(dispatch.issuerId, actorId, dispatch.countyId, state, targetPolicy)
            if (relation is DispatchAssessment.Rejected) return relation
            if (actor.nationId != dispatch.nationId) return reject(DispatchFailure.RELATION_CHANGED)
            if (!countyAvailable(dispatch.countyId, actorId, state)) return reject(DispatchFailure.COUNTY_OCCUPIED)
            relation
        } catch (_: IllegalArgumentException) { reject(DispatchFailure.STATE_UNAVAILABLE) }
    }

    private fun relationship(issuerId: Int, targetId: Int, countyId: Int, state: DispatchProjection,
        targetPolicy: DispatchTargetPolicy): DispatchAssessment {
        if (state.profile != RuleProfile.HWIHA) return reject(DispatchFailure.WRONG_RULE_PROFILE)
        val issuer = state.people.singleOrNull { it.id == issuerId } ?: return reject(DispatchFailure.ACTOR_NOT_FOUND)
        if (!issuer.isLord || issuer.nationId <= 0) return reject(DispatchFailure.NOT_LORD)
        if (targetPolicy == DispatchTargetPolicy.NPC_AUTOMATED && !issuer.isNpc)
            return reject(DispatchFailure.NPC_ISSUER_REQUIRED)
        val target = state.people.singleOrNull { it.id == targetId } ?: return reject(DispatchFailure.TARGET_NOT_FOUND)
        if (!target.isHuman && !(targetPolicy == DispatchTargetPolicy.NPC_AUTOMATED && target.isNpc))
            return reject(DispatchFailure.TARGET_NOT_HUMAN)
        if (issuerId == targetId || target.isLord) return reject(DispatchFailure.NOT_DIRECT_RETAINER)
        val cards = state.retainers.filter { it.generalId == targetId }
        val card = cards.singleOrNull()?.takeIf { it.masterId == issuerId } ?: return reject(DispatchFailure.NOT_DIRECT_RETAINER)
        if (target.nationId != issuer.nationId) return reject(DispatchFailure.DIFFERENT_NATION)
        if (state.counties.singleOrNull { it.id == countyId }?.nationId != issuer.nationId) return reject(DispatchFailure.INVALID_COUNTY)
        return DispatchAssessment.Eligible(issuer, target, card)
    }

    private fun countyAvailable(countyId: Int, targetId: Int, state: DispatchProjection): Boolean =
        state.people.filter { it.id != targetId }.none {
            val owner = state.counties.singleOrNull { county -> county.id == countyId }?.nationId
            val assignment = CountyAssignment.read(it.meta)
            val pending = DispatchState.read(it.meta)
            // A card placed (or queued) as this county's magistrate also holds the seat (배치 縣令, #193).
            val placement = PlacementState.read(it.meta)
            (assignment?.countyId == countyId && assignment.nationId == it.nationId && assignment.nationId == owner) ||
                (pending?.countyId == countyId && pending.status == DispatchStatus.PENDING && pending.nationId == it.nationId && pending.nationId == owner) ||
                (placement?.claimsMagistracy(countyId) == true && it.nationId == owner)
        }

    private fun reject(reason: DispatchFailure) = DispatchAssessment.Rejected(reason)
}
