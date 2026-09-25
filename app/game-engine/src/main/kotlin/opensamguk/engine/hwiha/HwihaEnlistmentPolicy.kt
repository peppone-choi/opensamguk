package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.*

typealias EnlistmentPolicyUnavailable = RenownBudgetFailure

sealed interface HwihaEnlistmentPolicyResult {
    data class Ready(
        val policy: EnlistmentPolicy,
        val unavailableLordReasons: Map<Int, EnlistmentPolicyUnavailable>,
    ) : HwihaEnlistmentPolicyResult
    data class Unavailable(val reason: EnlistmentPolicyUnavailable) : HwihaEnlistmentPolicyResult
}

/** Current-world projection only; API and engine share the logic budget authority. */
class HwihaEnlistmentPolicy(private val world: InMemoryTurnWorld) {
    fun current(request: EnlistmentRequest): HwihaEnlistmentPolicyResult {
        val result = EnlistmentBudget.assess(request.actorId, world.ruleProfile,
            world.listGenerals().map { general ->
                val stats = general.stats
                PersonPolicyInput(general.id, general.nationId, stats.leadership, stats.strength,
                    stats.intelligence, stats.politics, stats.charm, general.meta)
            },
            world.listRetainers().map { DirectPersonCard(it.id, it.masterGeneralId, it.generalId) },
        )
        return when (result) {
            is RenownBudgetResult.Ready -> HwihaEnlistmentPolicyResult.Ready(
                EnlistmentPolicy(result.acceptingLordIds, result.freeRenownByLord, result.actorCardCost),
                result.unavailableLordReasons,
            )
            is RenownBudgetResult.Unavailable -> HwihaEnlistmentPolicyResult.Unavailable(result.reason)
        }
    }
}
