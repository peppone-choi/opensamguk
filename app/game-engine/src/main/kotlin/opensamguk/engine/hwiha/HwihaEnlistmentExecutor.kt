package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.Retainer
import opensamguk.logic.input.*
import opensamguk.logic.retainer.RetainerRules

/** Supplied by the current renown/acceptance policy, never by client arguments. */
data class EnlistmentPolicy(
    val acceptingLordIds: Set<Int>,
    val freeRenownByLord: Map<Int, Int>,
    val actorCardCost: Int,
)

sealed interface EnlistmentExecution {
    data class Applied(val plan: EnlistmentPlan, val retainerId: Int) : EnlistmentExecution
    data class Rejected(val reason: EnlistmentFailure) : EnlistmentExecution
}

/**
 * A daemon-owned transition, not an intake endpoint or a committed result.
 * The caller owns the personal-turn RNG and the existing atomic slot/result/world flush.
 * Policy must recompute free capacity from current direct cards on each call; enlistment
 * occupies capacity by creating a card, rather than spending the lord's renown points.
 */
class HwihaEnlistmentExecutor(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val currentPolicy: ((EnlistmentRequest) -> EnlistmentPolicy)? = null,
) {
    fun execute(request: EnlistmentRequest, drawIndex: (Int) -> Int): EnlistmentExecution {
        if (world.ruleProfile != RuleProfile.HWIHA) {
            return EnlistmentExecution.Rejected(EnlistmentFailure.WRONG_RULE_PROFILE)
        }
        val generals = world.listGenerals()
        val nations = world.listNations().associateBy { it.id }
        val projection = HwihaEnlistmentProjection(world.ruleProfile,
            generals.map { general ->
                val stats = general.stats
                EnlistmentPersonRow(PersonPolicyInput(general.id, general.nationId, stats.leadership,
                    stats.strength, stats.intelligence, stats.politics, stats.charm, general.meta),
                    general.name, general.officerLevel, general.npcState, general.userId)
            },
            world.listRetainers().map { EnlistmentCardRow(it.id, it.masterGeneralId, it.generalId, it.name) },
            nations.keys,
        )
        val policy = currentPolicy?.invoke(request)
        val assessment = if (policy == null) HwihaEnlistmentPrecheck.assess(request, projection)
            else HwihaEnlistmentPrecheck.assess(request, projection,
                RenownBudgetResult.Ready(policy.acceptingLordIds, policy.freeRenownByLord, policy.actorCardCost, emptyMap()))
        if (assessment is EnlistmentAssessment.Rejected) return EnlistmentExecution.Rejected(assessment.reason)
        val plan = HwihaEnlistmentRules.select(assessment as EnlistmentAssessment.Eligible, drawIndex)
        // GENERAL can select a lord whose corrupt nation reference has no state row.
        val nation = nations[plan.nationId]
            ?: return EnlistmentExecution.Rejected(EnlistmentFailure.TARGET_NOT_FOUND)
        val byId = generals.associateBy { it.id }
        val actor = byId.getValue(plan.actorId)
        val changed = plan.joiningGeneralIds.map { id ->
            val before = byId.getValue(id)
            before to before.copy(nationId = plan.nationId, meta =
                if (id == plan.actorId) HwihaLordStatus.afterEnlistment(before.meta) else before.meta)
        }
        val joiningIds = plan.joiningGeneralIds.toSet()
        val count = generals.count { it.npcState != 5 && (it.nationId == nation.id || it.id in joiningIds) }
        val nextNation = nation.copy(meta = LinkedHashMap(nation.meta).apply { put("gennum", count) })
        val card = Retainer(
            id = world.allocateRetainerId(), masterGeneralId = plan.masterId,
            origin = RetainerRules.ORIGIN_EXISTING, generalId = actor.id, name = actor.name,
            relation = RetainerRules.RELATION_GUEST, role = RetainerRules.ROLE_NONE,
            hasOwnBugok = true, releasePolicy = RetainerRules.RELEASE_MUTUAL,
            loyalty = 50, task = RetainerRules.TASK_NONE,
        )
        for ((before, after) in changed) {
            recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
            world.applyGeneralDirtyFree(after)
        }
        recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(nextNation))
        world.applyNationDirtyFree(nextNation)
        world.createRetainer(card)
        return EnlistmentExecution.Applied(plan, card.id)
    }
}
