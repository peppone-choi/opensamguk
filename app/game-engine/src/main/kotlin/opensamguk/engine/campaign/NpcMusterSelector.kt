package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/** An unowned lord recalls its own detached corps from its current, visible position. */
internal class NpcMusterSelector(private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !PersonalTurn.hasNoInput(reserved) ||
            (catalog[MilitaryInput.MUSTER]?.deliveryState ?: InputDeliveryState.PLANNED) < InputDeliveryState.AI_READY)
            return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!NpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2 ||
            world.listRetainers().any { it.generalId == actorId }) return reserved
        val projection = DeploymentExecutor(world, opensamguk.engine.turn.ChangeRecorder(), topology, metrics)
            .projection() ?: return reserved
        if (MusterRules.assess(actorId, projection, topology, metrics, world.getState().meta)
            !is MusterAssessment.Eligible) return reserved
        return ReservedTurn(MilitaryInput.MUSTER, "{}", brief = MilitaryInput.MUSTER, rowExists = false)
    }
}
