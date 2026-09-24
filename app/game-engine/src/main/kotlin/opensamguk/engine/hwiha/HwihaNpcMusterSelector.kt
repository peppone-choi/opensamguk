package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*
import opensamguk.logic.world.LandMarchMetricSnapshot
import opensamguk.logic.world.StrategicTopologySnapshot

/** An unowned lord recalls its own detached corps from its current, visible position. */
internal class HwihaNpcMusterSelector(private val topology: StrategicTopologySnapshot,
    private val metrics: LandMarchMetricSnapshot,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !HwihaPersonalTurn.hasNoInput(reserved) ||
            (catalog[HwihaMilitaryInput.MUSTER]?.deliveryState ?: InputDeliveryState.PLANNED) < InputDeliveryState.AI_READY)
            return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!HwihaNpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2 ||
            world.listRetainers().any { it.generalId == actorId }) return reserved
        val projection = HwihaDeploymentExecutor(world, opensamguk.engine.turn.ChangeRecorder(), topology, metrics)
            .projection() ?: return reserved
        if (HwihaMusterRules.assess(actorId, projection, topology, metrics, world.getState().meta)
            !is HwihaMusterAssessment.Eligible) return reserved
        return ReservedTurn(HwihaMilitaryInput.MUSTER, "{}", brief = HwihaMilitaryInput.MUSTER, rowExists = false)
    }
}
