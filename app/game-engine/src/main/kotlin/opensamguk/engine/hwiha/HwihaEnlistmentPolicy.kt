package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.input.*

enum class EnlistmentPolicyUnavailable {
    WRONG_RULE_PROFILE, ACTOR_NOT_FOUND, MISSING_PERSON_POLICY, INVALID_PERSON_POLICY,
    INVALID_LORD_STATUS, INVALID_STATS, UNSUPPORTED_UNLINKED_CARD, LINKED_PERSON_NOT_FOUND,
    DUPLICATE_LINKED_PERSON, COST_OVERFLOW, CAPACITY_EXCEEDED,
}

sealed interface HwihaEnlistmentPolicyResult {
    data class Ready(
        val policy: EnlistmentPolicy,
        val unavailableLordReasons: Map<Int, EnlistmentPolicyUnavailable>,
    ) : HwihaEnlistmentPolicyResult
    data class Unavailable(val reason: EnlistmentPolicyUnavailable) : HwihaEnlistmentPolicyResult
}

/**
 * Recomputed from current direct person cards. Source metadata is a seed contract, not verification.
 * Named-unit cards have no model here and are unsupported; this is not a complete retinue budget.
 */
class HwihaEnlistmentPolicy(private val world: InMemoryTurnWorld) {
    private class Invalid(val reason: EnlistmentPolicyUnavailable) : RuntimeException()
    private fun unavailable(reason: EnlistmentPolicyUnavailable): Nothing = throw Invalid(reason)
    private fun person(general: TurnGeneral): HwihaPersonPolicyState = try {
        HwihaPersonPolicyState.read(general.meta)
            ?: unavailable(EnlistmentPolicyUnavailable.MISSING_PERSON_POLICY)
    } catch (_: IllegalArgumentException) {
        unavailable(EnlistmentPolicyUnavailable.INVALID_PERSON_POLICY)
    }
    private fun cost(general: TurnGeneral): Int {
        person(general)
        val stats = general.stats
        return try {
            HwihaRenownRules.personCost(stats.leadership, stats.strength, stats.intelligence, stats.politics, stats.charm)
        } catch (_: IllegalArgumentException) {
            unavailable(EnlistmentPolicyUnavailable.INVALID_STATS)
        }
    }

    fun current(request: EnlistmentRequest): HwihaEnlistmentPolicyResult {
        if (world.ruleProfile != RuleProfile.HWIHA) return HwihaEnlistmentPolicyResult.Unavailable(EnlistmentPolicyUnavailable.WRONG_RULE_PROFILE)
        val generals = world.listGenerals().associateBy { it.id }
        val actor = generals[request.actorId]
            ?: return HwihaEnlistmentPolicyResult.Unavailable(EnlistmentPolicyUnavailable.ACTOR_NOT_FOUND)
        // The executor reads every general's lord status, including unaffiliated people.
        val lordStatuses = try {
            generals.mapValues { (_, general) -> HwihaLordStatus.read(general.meta) }
        } catch (_: IllegalArgumentException) {
            return HwihaEnlistmentPolicyResult.Unavailable(EnlistmentPolicyUnavailable.INVALID_LORD_STATUS)
        }
        val actorCost = try { cost(actor) } catch (e: Invalid) {
            return HwihaEnlistmentPolicyResult.Unavailable(e.reason)
        }
        val accepting = linkedSetOf<Int>()
        val budgets = linkedMapOf<Int, Int>()
        val failures = linkedMapOf<Int, EnlistmentPolicyUnavailable>()
        for (lord in generals.values.sortedBy { it.id }) {
            if (lord.nationId <= 0) continue
            if (lordStatuses[lord.id] != true) continue
            try {
                val state = person(lord)
                if (state.acceptsEnlistment) accepting.add(lord.id)
                var occupied = 0L
                val seen = mutableSetOf<Int>()
                for (card in world.retainersOf(lord.id).sortedBy { it.id }) {
                    val id = card.generalId ?: unavailable(EnlistmentPolicyUnavailable.UNSUPPORTED_UNLINKED_CARD)
                    if (!seen.add(id)) unavailable(EnlistmentPolicyUnavailable.DUPLICATE_LINKED_PERSON)
                    val linked = generals[id] ?: unavailable(EnlistmentPolicyUnavailable.LINKED_PERSON_NOT_FOUND)
                    occupied += cost(linked).toLong()
                    if (occupied > Int.MAX_VALUE) unavailable(EnlistmentPolicyUnavailable.COST_OVERFLOW)
                }
                if (occupied > state.renownCapacity) unavailable(EnlistmentPolicyUnavailable.CAPACITY_EXCEEDED)
                budgets[lord.id] = state.renownCapacity - occupied.toInt()
            } catch (e: Invalid) {
                failures[lord.id] = e.reason
            }
        }
        return HwihaEnlistmentPolicyResult.Ready(EnlistmentPolicy(accepting, budgets, actorCost), failures)
    }
}
