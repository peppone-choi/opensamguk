package opensamguk.logic.input

/** Common committed/API or current/engine projection; contains no city or asset authority. */
data class EnlistmentPersonRow(
    val policy: PersonPolicyInput,
    val name: String,
    val officerLevel: Int,
    val npcState: Int,
    val userId: String?,
)
data class EnlistmentCardRow(val id: Int, val masterId: Int, val generalId: Int?, val name: String)
data class HwihaEnlistmentProjection(
    val profile: RuleProfile,
    val persons: List<EnlistmentPersonRow>,
    val cards: List<EnlistmentCardRow>,
    val nationIds: Set<Int>,
)

/** One authority for budget, sovereign selection, human control, bonds and name conflicts. */
object HwihaEnlistmentPrecheck {
    fun assess(request: EnlistmentRequest, state: HwihaEnlistmentProjection): EnlistmentAssessment =
        assess(request, state, HwihaEnlistmentBudget.assess(request.actorId, state.profile,
            state.persons.map { it.policy }, state.cards.map { DirectPersonCard(it.id, it.masterId, it.generalId) }))

    /** Server-policy injection for transition consumers; never populated from client arguments. */
    fun assess(request: EnlistmentRequest, state: HwihaEnlistmentProjection, budget: RenownBudgetResult): EnlistmentAssessment {
        fun deny(reason: EnlistmentFailure) = EnlistmentAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return deny(EnlistmentFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || (request.mode == EnlistmentMode.RANDOM) != (request.targetId == null) ||
            request.targetId?.let { it <= 0 } == true) return deny(EnlistmentFailure.INVALID_REQUEST)
        if (state.persons.map { it.policy.id }.toSet().size != state.persons.size ||
            state.persons.any { it.policy.id <= 0 || it.policy.nationId < 0 } ||
            state.cards.map { it.id }.toSet().size != state.cards.size || state.cards.any { it.id <= 0 }) {
            return deny(EnlistmentFailure.INVALID_RETINUE)
        }
        val actor = state.persons.firstOrNull { it.policy.id == request.actorId }
            ?: return deny(EnlistmentFailure.ACTOR_NOT_FOUND)
        if (budget is RenownBudgetResult.Unavailable) return deny(EnlistmentFailure.POLICY_UNAVAILABLE)
        budget as RenownBudgetResult.Ready
        if (request.actorId in budget.unavailableOwnerReasons) return deny(EnlistmentFailure.POLICY_UNAVAILABLE)
        val generals = try {
            state.persons.map { person ->
                EnlistmentGeneral(person.policy.id, person.policy.nationId, HwihaLordStatus.read(person.policy.meta),
                    person.npcState < 2 || (!person.userId.isNullOrBlank() &&
                        person.userId.toLongOrNull()?.let { it <= 0 } != true))
            }
        } catch (_: IllegalArgumentException) { return deny(EnlistmentFailure.POLICY_UNAVAILABLE) }
        val snapshot = EnlistmentSnapshot(state.profile, generals,
            state.cards.mapNotNull { it.generalId?.let { id -> EnlistmentBond(it.masterId, id) } },
            state.persons.filter { it.officerLevel == 12 && it.policy.nationId in state.nationIds }
                .groupBy { it.policy.nationId }.mapNotNull { (nationId, candidates) ->
                    candidates.singleOrNull()?.let { nationId to it.policy.id }
                }.toMap(),
            budget.acceptingLordIds, budget.freeRenownByLord, budget.actorCardCost,
            state.cards.filter { it.name == actor.name }.map { it.masterId }.toSet(),
            budget.unavailableLordReasons.keys,
        )
        val assessed = HwihaEnlistmentRules.assess(request, snapshot)
        if (assessed is EnlistmentAssessment.Eligible && assessed.choices.any { plan ->
                plan.joiningGeneralIds.any { it in budget.unavailableOwnerReasons }
            }) return deny(EnlistmentFailure.POLICY_UNAVAILABLE)
        if (assessed is EnlistmentAssessment.Eligible && assessed.choices.any { it.nationId !in state.nationIds }) {
            return deny(EnlistmentFailure.TARGET_NOT_FOUND)
        }
        return assessed
    }
}
