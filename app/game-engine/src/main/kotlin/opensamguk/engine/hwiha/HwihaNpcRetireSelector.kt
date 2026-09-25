package opensamguk.engine.hwiha

import opensamguk.common.constants.GameConst
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.input.*

/** An old NPC may name the lowest-id eligible direct successor without hidden information. */
internal class HwihaNpcRetireSelector(private val context: HwihaDomesticContext,
    private val catalog: InputCatalog = InputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists ||
            !HwihaPersonalTurn.hasNoInput(reserved) ||
            catalog[RetireInput.INPUT_ID]?.deliveryState?.hasHandler != true) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!HwihaNpcDeploySelector.isUnowned(actor.userId) || actor.npcState !in 2..4 ||
            actor.age < GameConst.retirementYear) return reserved
        val state = context.projection(world)
        val successor = state.cards.filter { it.masterId == actorId && it.generalId != null }
            .mapNotNull { it.generalId }.distinct().sorted().firstOrNull {
                RetireRules.assess(RetireRequest(actorId, it), state) is RetireAssessment.Eligible
            } ?: return reserved
        return ReservedTurn(RetireInput.INPUT_ID,
            RetireInput.canonicalJson(RetireRequest(actorId, successor)),
            brief = RetireInput.INPUT_ID, rowExists = false)
    }
}
