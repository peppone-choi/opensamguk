package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.logic.domain.NpcType
import opensamguk.logic.input.*

/** Prefer first assignments; repeat only after the response deadline and loss of a valid seat. */
internal object NpcDispatchSelector {
    private val catalog by lazy { InputCatalog.load() }
    private data class Candidate(val target: TurnGeneral, val last: DispatchState?)

    fun select(world: InMemoryTurnWorld, issuerId: Int, executor: DispatchExecutor): DispatchRequest? {
        if (world.ruleProfile != RuleProfile.HWIHA ||
            !AiPolicyRegistry.selectable(catalog, "court.dispatch", AiSelectorKey.COURT_DISPATCH)) return null
        val issuer = world.getGeneralById(issuerId) ?: return null
        if (issuer.npcState != NpcType.NPC_LITE || issuer.nationId <= 0 ||
            issuer.meta[LordStatus.META_KEY] != true ||
            (!issuer.userId.isNullOrBlank() && issuer.userId.toLongOrNull()?.let { it <= 0 } != true) ||
            QueuedDispatch.META_KEY in issuer.meta) return null
        if (world.listRetainers().any { it.generalId == issuerId }) return null
        val directTargetIds = world.listRetainers().mapNotNull { card ->
            card.generalId?.let { it to card }
        }.groupBy { it.first }
            .filterValues { cards -> cards.size == 1 && cards.single().second.masterGeneralId == issuerId }.keys
        val now = world.getState().let { Phase(it.currentYear, it.currentMonth, it.currentPhase) }
        val targets = directTargetIds.mapNotNull(world::getGeneralById).mapNotNull { target ->
            if (target.id == issuerId || target.nationId != issuer.nationId ||
                target.meta[LordStatus.META_KEY] != false ||
                !((target.userId?.toLongOrNull() ?: 0) > 0 ||
                    (target.npcState == NpcType.NPC_LITE &&
                        (target.userId.isNullOrBlank() || target.userId.toLongOrNull()?.let { it <= 0 } == true))))
                return@mapNotNull null
            val last = try { DispatchState.read(target.meta) } catch (_: IllegalArgumentException) { return@mapNotNull null }
            if (last?.status == DispatchStatus.PENDING || (last != null && now < last.dueAt)) return@mapNotNull null
            val assignment = try { CountyAssignment.read(target.meta) } catch (_: IllegalArgumentException) { return@mapNotNull null }
            if (assignment != null && executor.assessAssignment(target.id, assignment) is DispatchAssessment.Eligible)
                return@mapNotNull null
            Candidate(target, last)
        }.sortedWith(compareBy<Candidate> { it.last?.issuedAt }.thenBy { it.target.id })
        if (targets.isEmpty()) return null
        val counties = world.listCities().filter {
            it.id in world.administrativeCountyIds && it.nationId == issuer.nationId
        }.sortedBy { it.id }
        for (candidate in targets) for (county in counties) {
            if (county.id == candidate.last?.countyId) continue
            val request = DispatchRequest(issuerId, candidate.target.id, county.id)
            if (executor.assess(request, DispatchTargetPolicy.NPC_AUTOMATED) is DispatchAssessment.Eligible)
                return request
        }
        return null
    }

    fun dispatchId(world: InMemoryTurnWorld, request: DispatchRequest): String = world.getState().let {
        "npc-dispatch:${world.worldId.value}:${request.actorId}:${request.targetGeneralId}:${it.currentYear}:${it.currentMonth}:${it.currentPhase}"
    }
}
