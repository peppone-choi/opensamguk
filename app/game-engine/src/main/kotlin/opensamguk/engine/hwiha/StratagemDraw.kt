package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.Phase
import opensamguk.logic.input.StratagemHand
import opensamguk.logic.input.RuleProfile

/** Actual personal-turn supply, persisted with the action and phase stamp by the existing flush. */
class StratagemDraw(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun onTurn(generalId:Int) {
        if(world.ruleProfile!=RuleProfile.HWIHA)return
        val before=requireNotNull(world.getGeneralById(generalId))
        // A held NPC has no independent personal hand; contributions belong to its immediate holder.
        if (before.npcState == 2 && world.listRetainers().any { it.generalId == generalId }) {
            if (StratagemHand.META_KEY in before.meta) {
                val after = before.copy(meta = before.meta - StratagemHand.META_KEY)
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
                world.applyGeneralDirtyFree(after)
            }
            return
        }
        val state=world.getState()
        val phase=Phase(state.currentYear,state.currentMonth,state.currentPhase)
        val current=StratagemHand.read(before.meta,generalId)
        val next=(current ?: StratagemHand.initial(generalId,phase))
            .withContributions(PersonDeckProjection.forHolder(world, generalId)).advance(phase)
        if(next===current)return
        val after=before.copy(meta=before.meta+(StratagemHand.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before),PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }
}
