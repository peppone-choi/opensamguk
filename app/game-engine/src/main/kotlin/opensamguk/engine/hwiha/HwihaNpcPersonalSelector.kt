package opensamguk.engine.hwiha

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*

/** NPCs use the same current-condition rules as player reservations. */
internal class HwihaNpcPersonalSelector(private val context: HwihaDomesticContext,
    private val design: HwihaPersonalDesign = HwihaPersonalDesign.CANON,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !HwihaPersonalTurn.hasNoInput(reserved) ||
            design.status != HwihaPersonalDesign.CONFIRMED) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!HwihaNpcDeploySelector.isUnowned(actor.userId) || actor.npcState < 2 ||
            world.listRetainers().any { it.generalId == actorId } || HwihaCorpsOrder.META_KEY in actor.meta)
            return reserved
        val state = context.projection(world)
        fun eligible(request: HwihaPersonalRequest) =
            catalog[request.inputId]?.deliveryState?.hasHandler == true &&
                HwihaPersonalRules.assess(request, state) is HwihaPersonalAssessment.Eligible
        val heal = HwihaPersonalRequest(actorId, HwihaPersonalInput.RECUPERATE)
        if (eligible(heal)) return order(heal)
        val train = HwihaTrainingStat.entries.firstOrNull { eligible(HwihaPersonalRequest(actorId,
            HwihaPersonalInput.SELF_TRAIN, it)) }
        if (train != null) return order(HwihaPersonalRequest(actorId, HwihaPersonalInput.SELF_TRAIN, train))
        val travel = HwihaPersonalRequest(actorId, HwihaPersonalInput.TRAVEL)
        return if (eligible(travel)) order(travel) else reserved
    }

    private fun order(request: HwihaPersonalRequest) = ReservedTurn(request.inputId,
        HwihaPersonalInput.canonicalJson(request), brief = request.inputId, rowExists = false)
}
