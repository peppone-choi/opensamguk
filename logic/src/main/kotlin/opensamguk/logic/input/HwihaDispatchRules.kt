package opensamguk.logic.input

data class DispatchPerson(val id: Int, val nationId: Int, val isLord: Boolean, val isHuman: Boolean,
    val meta: Map<String, Any?>)
data class DispatchRetainer(val id: Int, val masterId: Int, val generalId: Int, val loyalty: Int)
data class DispatchCounty(val id: Int, val nationId: Int)
/** Counties must come from validated administrative classification, not every map city. */
data class HwihaDispatchProjection(val profile: RuleProfile, val people: List<DispatchPerson>,
    val retainers: List<DispatchRetainer>, val counties: List<DispatchCounty>)

enum class DispatchFailure {
    WRONG_RULE_PROFILE, ACTOR_NOT_FOUND, NOT_LORD, TARGET_NOT_FOUND, TARGET_NOT_HUMAN,
    NOT_DIRECT_RETAINER, DIFFERENT_NATION, INVALID_COUNTY, COUNTY_OCCUPIED, ALREADY_PENDING,
    NO_DISPATCH, ALREADY_RESOLVED, NOT_RECIPIENT, RELATION_CHANGED, POLICY_UNAVAILABLE, STATE_UNAVAILABLE,
}
sealed interface DispatchAssessment {
    data class Eligible(val issuer: DispatchPerson, val target: DispatchPerson, val card: DispatchRetainer) : DispatchAssessment
    data class Rejected(val reason: DispatchFailure) : DispatchAssessment
}

/** Shared admission/execution checks. No RNG, mutation, persistence or clock access. */
object HwihaDispatchRules {
    fun assess(request: DispatchRequest, state: HwihaDispatchProjection): DispatchAssessment {
        val relation = relationship(request.actorId, request.targetGeneralId, request.countyId, state)
        if (relation !is DispatchAssessment.Eligible) return relation
        return try {
            val pending = HwihaDispatchState.read(relation.target.meta)
            when {
                pending?.status == DispatchStatus.PENDING -> reject(DispatchFailure.ALREADY_PENDING)
                !countyAvailable(request.countyId, request.targetGeneralId, state) -> reject(DispatchFailure.COUNTY_OCCUPIED)
                else -> relation
            }
        } catch (_: IllegalArgumentException) { reject(DispatchFailure.STATE_UNAVAILABLE) }
    }

    /** Shared cost precheck: a refusal at the deadline is already an acceptance. */
    fun assessReply(request: DispatchReplyRequest, now: HwihaPhase, state: HwihaDispatchProjection): DispatchAssessment {
        val authority = assessReply(request.actorId, request.dispatchId, state)
        if (authority !is DispatchAssessment.Eligible) return authority
        val dispatch = HwihaDispatchState.read(authority.target.meta)!!
        if (!request.accept && now < dispatch.dueAt) {
            val policy = try { HwihaPersonPolicyState.read(authority.target.meta) }
                catch (_: IllegalArgumentException) { null }
            if (policy == null) return reject(DispatchFailure.POLICY_UNAVAILABLE)
        }
        return authority
    }

    /** Even automatic acceptance must revalidate the original relationship and county. */
    fun assessReply(actorId: Int, dispatchId: String, state: HwihaDispatchProjection): DispatchAssessment {
        if (state.profile != RuleProfile.HWIHA) return reject(DispatchFailure.WRONG_RULE_PROFILE)
        val actor = state.people.singleOrNull { it.id == actorId } ?: return reject(DispatchFailure.ACTOR_NOT_FOUND)
        return try {
            val dispatch = HwihaDispatchState.read(actor.meta) ?: return reject(DispatchFailure.NO_DISPATCH)
            if (dispatch.dispatchId != dispatchId || dispatch.targetId != actorId) return reject(DispatchFailure.NOT_RECIPIENT)
            if (dispatch.status != DispatchStatus.PENDING) return reject(DispatchFailure.ALREADY_RESOLVED)
            val relation = relationship(dispatch.issuerId, actorId, dispatch.countyId, state)
            if (relation is DispatchAssessment.Rejected) return relation
            if (actor.nationId != dispatch.nationId) return reject(DispatchFailure.RELATION_CHANGED)
            if (!countyAvailable(dispatch.countyId, actorId, state)) return reject(DispatchFailure.COUNTY_OCCUPIED)
            relation
        } catch (_: IllegalArgumentException) { reject(DispatchFailure.STATE_UNAVAILABLE) }
    }

    private fun relationship(issuerId: Int, targetId: Int, countyId: Int, state: HwihaDispatchProjection): DispatchAssessment {
        if (state.profile != RuleProfile.HWIHA) return reject(DispatchFailure.WRONG_RULE_PROFILE)
        val issuer = state.people.singleOrNull { it.id == issuerId } ?: return reject(DispatchFailure.ACTOR_NOT_FOUND)
        if (!issuer.isLord || issuer.nationId <= 0) return reject(DispatchFailure.NOT_LORD)
        val target = state.people.singleOrNull { it.id == targetId } ?: return reject(DispatchFailure.TARGET_NOT_FOUND)
        if (!target.isHuman) return reject(DispatchFailure.TARGET_NOT_HUMAN)
        if (issuerId == targetId || target.isLord) return reject(DispatchFailure.NOT_DIRECT_RETAINER)
        val cards = state.retainers.filter { it.generalId == targetId }
        val card = cards.singleOrNull()?.takeIf { it.masterId == issuerId } ?: return reject(DispatchFailure.NOT_DIRECT_RETAINER)
        if (target.nationId != issuer.nationId) return reject(DispatchFailure.DIFFERENT_NATION)
        if (state.counties.singleOrNull { it.id == countyId }?.nationId != issuer.nationId) return reject(DispatchFailure.INVALID_COUNTY)
        return DispatchAssessment.Eligible(issuer, target, card)
    }

    private fun countyAvailable(countyId: Int, targetId: Int, state: HwihaDispatchProjection): Boolean =
        state.people.filter { it.id != targetId }.none {
            val owner = state.counties.singleOrNull { county -> county.id == countyId }?.nationId
            val assignment = HwihaCountyAssignment.read(it.meta)
            val pending = HwihaDispatchState.read(it.meta)
            (assignment?.countyId == countyId && assignment.nationId == it.nationId && assignment.nationId == owner) ||
                (pending?.countyId == countyId && pending.status == DispatchStatus.PENDING && pending.nationId == it.nationId && pending.nationId == owner)
        }

    private fun reject(reason: DispatchFailure) = DispatchAssessment.Rejected(reason)
}
