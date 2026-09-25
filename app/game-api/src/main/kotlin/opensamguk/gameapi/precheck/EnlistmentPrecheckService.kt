package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.*
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Read-only committed snapshot; execution repeats the identical pure assessment on current state. */
@Service
class EnlistmentPrecheckService(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val retainers: RetainerReadRepository,
    private val worlds: WorldStateReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun assess(request: EnlistmentRequest): EnlistmentAssessment {
        return when (val snapshot = snapshot()) {
            is Snapshot.Unavailable -> EnlistmentAssessment.Rejected(snapshot.reason)
            is Snapshot.Ready -> EnlistmentPrecheck.assess(request, snapshot.state)
        }
    }

    /** An owned read projection; never disclose policy metadata or the actor's joining subtree. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, ownerUserId: Long): EnlistmentOptions {
        val actor = generals.findById(actorId).orElse(null)
        if (ownerUserId <= 0 || actor?.userId?.toLongOrNull() != ownerUserId) throw EnlistmentOptionsForbidden()
        val snapshot = snapshot()
        if (snapshot is Snapshot.Unavailable) {
            return EnlistmentOptions(options = listOf(EnlistmentOption(EnlistmentMode.RANDOM, null, "무작위 출사",
                EnlistmentOptionAvailability.blocked(snapshot.reason))))
        }
        snapshot as Snapshot.Ready
        val state = snapshot.state
        val budget = EnlistmentBudget.assess(actorId, state.profile, state.persons.map { it.policy },
            state.cards.map { DirectPersonCard(it.id, it.masterId, it.generalId) })
        if (budget is RenownBudgetResult.Unavailable) {
            return EnlistmentOptions(options = listOf(EnlistmentOption(EnlistmentMode.RANDOM, null, "무작위 출사",
                EnlistmentOptionAvailability.blocked(EnlistmentFailure.POLICY_UNAVAILABLE))))
        }
        fun option(mode: EnlistmentMode, id: Int?, label: String): EnlistmentOption {
            val assessment = EnlistmentPrecheck.assess(EnlistmentRequest(actorId, mode, id), state, budget)
            val availability = when (assessment) {
                is EnlistmentAssessment.Eligible -> EnlistmentOptionAvailability("AVAILABLE")
                is EnlistmentAssessment.Rejected -> EnlistmentOptionAvailability.blocked(assessment.reason)
            }
            return EnlistmentOption(mode, id, label, availability)
        }
        return EnlistmentOptions(options = buildList {
            add(option(EnlistmentMode.RANDOM, null, "무작위 출사"))
            snapshot.nations.filter { it.id > 0 }.sortedBy { it.id }.forEach {
                add(option(EnlistmentMode.NATION, it.id, it.name))
            }
            state.persons.filter { it.policy.id != actorId }.sortedBy { it.policy.id }.forEach {
                add(option(EnlistmentMode.GENERAL, it.policy.id, it.name))
            }
        })
    }

    private sealed interface Snapshot {
        data class Unavailable(val reason: EnlistmentFailure) : Snapshot
        data class Ready(val state: EnlistmentProjection, val nations: List<NationReadEntity>) : Snapshot
    }

    private fun snapshot(): Snapshot {
        val world = worlds.findProcessWorld() ?: return Snapshot.Unavailable(EnlistmentFailure.POLICY_UNAVAILABLE)
        val profile = opensamguk.logic.input.WorldRuleProfile.resolve(world.config)
            ?: return Snapshot.Unavailable(EnlistmentFailure.POLICY_UNAVAILABLE)
        if (profile != RuleProfile.HWIHA) return Snapshot.Unavailable(EnlistmentFailure.WRONG_RULE_PROFILE)
        val nationRows = nations.findAll()
        val state = EnlistmentProjection(profile,
            generals.findAll().map { general ->
                // Do not use the legacy toLogic conversion: it does not project politics/charm.
                EnlistmentPersonRow(PersonPolicyInput(general.id, general.nationId, general.leadership,
                    general.strength, general.intel, general.politics, general.charm, general.meta),
                    general.name, general.officerLevel, general.npcState, general.userId)
            },
            retainers.findAll().map { EnlistmentCardRow(it.id, it.masterGeneralId, it.generalId, it.name) },
            nationRows.map { it.id }.toSet(),
        )
        return Snapshot.Ready(state, nationRows)
    }
}

class EnlistmentOptionsForbidden : RuntimeException()
data class EnlistmentOptions(
    val result: Boolean = true,
    val inputId: String = "action.enlist",
    val maxReservedTurns: Int = 12,
    val options: List<EnlistmentOption>,
)
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
data class EnlistmentOption(
    val mode: EnlistmentMode,
    val targetId: Int?,
    val label: String,
    val availability: EnlistmentOptionAvailability,
)
data class EnlistmentOptionAvailability(val status: String, val code: String? = null, val reason: String? = null) {
    companion object {
        fun blocked(reason: EnlistmentFailure) = EnlistmentOptionAvailability("BLOCKED", reason.name, reason.message)
    }
}
