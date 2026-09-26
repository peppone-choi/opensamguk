package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.AiPolicyBinding
import opensamguk.logic.input.AiPolicyRegistry
import opensamguk.logic.input.AiSelectorKey
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/** Runs only registered NPC policies, preserving the existing priority and shared human handlers. */
internal class NpcAiTurnSelector(
    topology: StrategicTopologySnapshot,
    metrics: LandMarchMetricSnapshot,
    context: DomesticContext,
    private val catalog: InputCatalog = InputCatalog.load(),
) {
    private val selectors: Map<AiSelectorKey, (InMemoryTurnWorld, Int, ReservedTurn) -> ReservedTurn> = mapOf(
        AiSelectorKey.DEPLOY to NpcDeploySelector(topology, metrics)::select,
        AiSelectorKey.CITY_MILITARY to NpcCityMilitarySelector(context, catalog = catalog)::select,
        AiSelectorKey.PEOPLE to NpcPeopleSelector(context, catalog = catalog)::select,
        AiSelectorKey.FIELD to NpcFieldSelector(context, catalog)::select,
        AiSelectorKey.PERSONAL to NpcPersonalSelector(context, catalog = catalog)::select,
    )

    init {
        val generalTurnKeys = AiPolicyRegistry.bindings.values.filterIsInstance<AiPolicyBinding.Selector>()
            .map { it.key }.toSet() - setOf(AiSelectorKey.ENLIST, AiSelectorKey.COURT_DISPATCH)
        require(selectors.keys == generalTurnKeys) { "NPC general-turn selector routes do not match aiPolicyId registry" }
    }

    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        var selected = reserved
        for ((key, selector) in selectors) {
            val next = selector(world, actorId, selected)
            if (next !== selected) {
                check(AiPolicyRegistry.selectable(catalog, next.actionCode, key)) {
                    "NPC selector $key produced unregistered or undelivered input ${next.actionCode}"
                }
            }
            selected = next
        }
        return selected
    }
}
