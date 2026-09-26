package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.domain.NpcType
import opensamguk.logic.input.*

/** A first assignment only. Existing dispatch history prevents automatic refusal/cancellation loops. */
internal object NpcDispatchSelector {
    private val catalog by lazy { InputCatalog.load() }

    fun select(world: InMemoryTurnWorld, issuerId: Int, executor: DispatchExecutor): DispatchRequest? {
        if (world.ruleProfile != RuleProfile.HWIHA ||
            !AiPolicyRegistry.selectable(catalog, "court.dispatch", AiSelectorKey.COURT_DISPATCH)) return null
        val issuer = world.getGeneralById(issuerId) ?: return null
        if (issuer.npcState != NpcType.NPC_LITE || issuer.nationId <= 0 ||
            issuer.meta[LordStatus.META_KEY] != true ||
            (!issuer.userId.isNullOrBlank() && issuer.userId.toLongOrNull()?.let { it <= 0 } != true) ||
            QueuedDispatch.META_KEY in issuer.meta) return null
        if (world.listRetainers().any { it.generalId == issuerId }) return null
        val directTargetIds = world.listRetainers().filter { it.generalId != null }
            .groupBy { it.generalId!! }
            .filterValues { cards -> cards.size == 1 && cards.single().masterGeneralId == issuerId }.keys
        val targets = directTargetIds.mapNotNull(world::getGeneralById).filter {
            it.id != issuerId && it.nationId == issuer.nationId &&
                (it.userId?.toLongOrNull() ?: 0) > 0 && it.meta[LordStatus.META_KEY] == false &&
                DispatchState.META_KEY !in it.meta && CountyAssignment.META_KEY !in it.meta
        }.sortedBy { it.id }
        if (targets.isEmpty()) return null
        val counties = world.listCities().filter {
            it.id in world.administrativeCountyIds && it.nationId == issuer.nationId
        }.sortedBy { it.id }
        for (target in targets) for (county in counties) {
            val request = DispatchRequest(issuerId, target.id, county.id)
            if (executor.assess(request) is DispatchAssessment.Eligible) return request
        }
        return null
    }

    fun dispatchId(world: InMemoryTurnWorld, request: DispatchRequest): String = world.getState().let {
        "npc-dispatch:${world.worldId.value}:${request.actorId}:${request.targetGeneralId}:${it.currentYear}:${it.currentMonth}:${it.currentPhase}"
    }
}
