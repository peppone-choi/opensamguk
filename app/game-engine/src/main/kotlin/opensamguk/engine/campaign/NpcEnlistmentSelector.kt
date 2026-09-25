package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.domain.NpcType
import opensamguk.logic.input.*

/** Chooses an input only; the existing handler owns RNG, transition and recording. */
internal object NpcEnlistmentSelector {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        // Other NPC variants have separate lifecycle semantics and are not opted in.
        if (actor.npcState != NpcType.NPC_LITE || actor.nationId != 0 ||
            actor.meta[LordStatus.META_KEY] != false ||
            (!actor.userId.isNullOrBlank() && actor.userId.toLongOrNull()?.let { it <= 0 } != true) ||
            world.listRetainers().any { it.generalId == actorId }) return reserved
        val request = EnlistmentRequest(actorId, EnlistmentMode.RANDOM)
        val projection = world.enlistmentProjection()
        if (EnlistmentPrecheck.assess(request, projection) !is EnlistmentAssessment.Eligible) return reserved
        return ReservedTurn(EnlistmentHandler.INPUT_ID, EnlistmentInput.canonicalJson(request),
            brief = "출사", rowExists = false)
    }
}
