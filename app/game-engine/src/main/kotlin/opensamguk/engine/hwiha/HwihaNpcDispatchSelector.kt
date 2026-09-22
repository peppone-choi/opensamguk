package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.domain.NpcType
import opensamguk.logic.input.*

/** A first assignment only. Existing dispatch history prevents automatic refusal/cancellation loops. */
internal object HwihaNpcDispatchSelector {
    fun select(world: InMemoryTurnWorld, issuerId: Int, executor: HwihaDispatchExecutor): DispatchRequest? {
        if (world.ruleProfile != RuleProfile.HWIHA) return null
        val issuer = world.getGeneralById(issuerId) ?: return null
        if (issuer.npcState != NpcType.NPC_LITE || issuer.nationId <= 0 ||
            issuer.meta[HwihaLordStatus.META_KEY] != true ||
            (!issuer.userId.isNullOrBlank() && issuer.userId.toLongOrNull()?.let { it <= 0 } != true) ||
            HwihaQueuedDispatch.META_KEY in issuer.meta) return null
        val directTargetIds = world.listRetainers().filter { it.generalId != null }
            .groupBy { it.generalId!! }
            .filterValues { cards -> cards.size == 1 && cards.single().masterGeneralId == issuerId }.keys
        val targets = directTargetIds.mapNotNull(world::getGeneralById).filter {
            it.id != issuerId && it.nationId == issuer.nationId &&
                (it.userId?.toLongOrNull() ?: 0) > 0 && it.meta[HwihaLordStatus.META_KEY] == false &&
                HwihaDispatchState.META_KEY !in it.meta && HwihaCountyAssignment.META_KEY !in it.meta
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
