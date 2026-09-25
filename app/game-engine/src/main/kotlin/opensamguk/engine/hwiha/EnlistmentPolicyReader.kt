package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*

typealias EnlistmentPolicyUnavailable = RenownBudgetFailure

sealed interface EnlistmentPolicyResult {
    data class Ready(
        val policy: EnlistmentPolicy,
        val unavailableLordReasons: Map<Int, EnlistmentPolicyUnavailable>,
    ) : EnlistmentPolicyResult
    data class Unavailable(val reason: EnlistmentPolicyUnavailable) : EnlistmentPolicyResult
}

/** Current-world projection only; API and engine share the logic budget authority. */
class EnlistmentPolicyReader(private val world: InMemoryTurnWorld) {
    fun current(request: EnlistmentRequest): EnlistmentPolicyResult {
        val result = EnlistmentBudget.assess(request.actorId, world.ruleProfile,
            world.listGenerals().map { general ->
                val stats = general.stats
                PersonPolicyInput(general.id, general.nationId, stats.leadership, stats.strength,
                    stats.intelligence, stats.politics, stats.charm, general.meta)
            },
            world.listRetainers().map { DirectPersonCard(it.id, it.masterGeneralId, it.generalId) },
        )
        return when (result) {
            is RenownBudgetResult.Ready -> EnlistmentPolicyResult.Ready(
                EnlistmentPolicy(result.acceptingLordIds, result.freeRenownByLord, result.actorCardCost),
                result.unavailableLordReasons,
            )
            is RenownBudgetResult.Unavailable -> EnlistmentPolicyResult.Unavailable(result.reason)
        }
    }
}
