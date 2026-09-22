package opensamguk.logic.input

/** Projection of current persisted stats; no default stats or inferred provenance. */
data class PersonPolicyInput(
    val id: Int, val nationId: Int,
    val leadership: Int, val strength: Int, val intelligence: Int, val politics: Int, val charm: Int,
    val meta: Map<String, Any?>,
)
data class DirectPersonCard(val id: Int, val masterId: Int, val generalId: Int?)

enum class RenownBudgetFailure {
    WRONG_RULE_PROFILE, ACTOR_NOT_FOUND, MISSING_PERSON_POLICY, INVALID_PERSON_POLICY,
    INVALID_LORD_STATUS, INVALID_STATS, UNSUPPORTED_UNLINKED_CARD, LINKED_PERSON_NOT_FOUND,
    DUPLICATE_LINKED_PERSON, COST_OVERFLOW, CAPACITY_EXCEEDED,
}


sealed interface RenownBudgetResult {
    data class Ready(
        val acceptingLordIds: Set<Int>,
        val freeRenownByLord: Map<Int, Int>,
        val actorCardCost: Int,
        val unavailableLordReasons: Map<Int, RenownBudgetFailure>,
    ) : RenownBudgetResult
    data class Unavailable(val reason: RenownBudgetFailure) : RenownBudgetResult
}

/**
 * Shared API/engine budget calculation. Metadata is supplied by validated seeds, not verified here.
 * Only direct person cards count. Named-unit cards have no model here and remain unsupported.
 */
object HwihaEnlistmentBudget {
    private class Invalid(val reason: RenownBudgetFailure) : RuntimeException()
    private fun unavailable(reason: RenownBudgetFailure): Nothing = throw Invalid(reason)
    private fun person(general: PersonPolicyInput): HwihaPersonPolicyState = try {
        HwihaPersonPolicyState.read(general.meta)
            ?: unavailable(RenownBudgetFailure.MISSING_PERSON_POLICY)
    } catch (_: IllegalArgumentException) {
        unavailable(RenownBudgetFailure.INVALID_PERSON_POLICY)
    }
    private fun cost(general: PersonPolicyInput): Int {
        person(general)
        return try {
            HwihaRenownRules.personCost(general.leadership, general.strength, general.intelligence, general.politics, general.charm)
        } catch (_: IllegalArgumentException) {
            unavailable(RenownBudgetFailure.INVALID_STATS)
        }
    }

    fun assess(actorId: Int, profile: RuleProfile, persons: List<PersonPolicyInput>, cards: List<DirectPersonCard>): RenownBudgetResult {
        if (profile != RuleProfile.HWIHA) return RenownBudgetResult.Unavailable(RenownBudgetFailure.WRONG_RULE_PROFILE)
        val generals = persons.associateBy { it.id }
        val actor = generals[actorId]
            ?: return RenownBudgetResult.Unavailable(RenownBudgetFailure.ACTOR_NOT_FOUND)
        // The executor reads every general's lord status, including unaffiliated people.
        val lordStatuses = try {
            generals.mapValues { (_, general) -> HwihaLordStatus.read(general.meta) }
        } catch (_: IllegalArgumentException) {
            return RenownBudgetResult.Unavailable(RenownBudgetFailure.INVALID_LORD_STATUS)
        }
        val actorCost = try { cost(actor) } catch (e: Invalid) {
            return RenownBudgetResult.Unavailable(e.reason)
        }
        val accepting = linkedSetOf<Int>()
        val budgets = linkedMapOf<Int, Int>()
        val failures = linkedMapOf<Int, RenownBudgetFailure>()
        for (lord in generals.values.sortedBy { it.id }) {
            if (lord.nationId <= 0) continue
            if (lordStatuses[lord.id] != true) continue
            try {
                val state = person(lord)
                if (state.acceptsEnlistment) accepting.add(lord.id)
                var occupied = 0L
                val seen = mutableSetOf<Int>()
                for (card in cards.filter { it.masterId == lord.id }.sortedBy { it.id }) {
                    val id = card.generalId ?: unavailable(RenownBudgetFailure.UNSUPPORTED_UNLINKED_CARD)
                    if (!seen.add(id)) unavailable(RenownBudgetFailure.DUPLICATE_LINKED_PERSON)
                    val linked = generals[id] ?: unavailable(RenownBudgetFailure.LINKED_PERSON_NOT_FOUND)
                    occupied += cost(linked).toLong()
                    if (occupied > Int.MAX_VALUE) unavailable(RenownBudgetFailure.COST_OVERFLOW)
                }
                if (occupied > state.renownCapacity) unavailable(RenownBudgetFailure.CAPACITY_EXCEEDED)
                budgets[lord.id] = state.renownCapacity - occupied.toInt()
            } catch (e: Invalid) {
                failures[lord.id] = e.reason
            }
        }
        return RenownBudgetResult.Ready(accepting, budgets, actorCost, failures)
    }
}
