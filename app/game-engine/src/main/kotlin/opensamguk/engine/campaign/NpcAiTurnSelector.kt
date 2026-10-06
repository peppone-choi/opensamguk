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
    private val observationFactory: NpcObservationFactory,
    private val catalog: InputCatalog = InputCatalog.load(),
) {
    // Deployment still needs a sight-limited target projection. Keep that remaining world read
    // explicit so the other selectors cannot accidentally regain authoritative foreign state.
    private val deploy = NpcDeploySelector(topology, metrics)
    private val observedSelectors: Map<AiSelectorKey, (NpcObservation, Int, ReservedTurn) -> ReservedTurn> = linkedMapOf(
        AiSelectorKey.MUSTER to NpcMusterSelector(topology, metrics, catalog)::select,
        AiSelectorKey.CITY_MILITARY to NpcCityMilitarySelector(context, catalog = catalog)::select,
        AiSelectorKey.PEOPLE to NpcPeopleSelector(context, catalog = catalog)::select,
        AiSelectorKey.FIELD to NpcFieldSelector(context, catalog)::select,
        AiSelectorKey.PERSONAL to NpcPersonalSelector(context, catalog = catalog)::select,
    )

    internal val selectionOrder: List<AiSelectorKey> get() = listOf(AiSelectorKey.DEPLOY) + observedSelectors.keys

    init {
        val generalTurnKeys = AiPolicyRegistry.bindings.values.filterIsInstance<AiPolicyBinding.Selector>()
            .map { it.key }.toSet() - setOf(AiSelectorKey.ENLIST, AiSelectorKey.COURT_DISPATCH,
                AiSelectorKey.COURT_REWARD)
        require(selectionOrder.toSet() == generalTurnKeys) {
            "NPC general-turn selector routes do not match aiPolicyId registry"
        }
    }

    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (reserved.rowExists || !PersonalTurn.hasNoInput(reserved)) return reserved
        // If the pinned sight index or actor state cannot be projected, leave the reservation
        // untouched. Falling back to world-backed selectors would bypass the visibility boundary.
        val observation = observationFactory.build(world, actorId) ?: return reserved
        var selected = deploy.select(world, actorId, reserved)
        validateSelection(AiSelectorKey.DEPLOY, reserved, selected)
        for ((key, selector) in observedSelectors) {
            val next = selector(observation, actorId, selected)
            validateSelection(key, selected, next)
            selected = next
        }
        return selected
    }

    private fun validateSelection(key: AiSelectorKey, previous: ReservedTurn, next: ReservedTurn) {
        if (next !== previous) {
            check(AiPolicyRegistry.selectable(catalog, next.actionCode, key)) {
                "NPC selector $key produced unregistered or undelivered input ${next.actionCode}"
            }
        }
    }
}
