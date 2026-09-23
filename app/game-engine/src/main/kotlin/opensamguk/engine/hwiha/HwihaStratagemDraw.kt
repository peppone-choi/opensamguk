package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.logic.input.HwihaPhase
import opensamguk.logic.input.HwihaStratagemHand
import opensamguk.logic.input.RuleProfile

/** Actual personal-turn supply, persisted with the action and phase stamp by the existing flush. */
class HwihaStratagemDraw(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun onTurn(generalId:Int) {
        if(world.ruleProfile!=RuleProfile.HWIHA)return
        val before=requireNotNull(world.getGeneralById(generalId))
        // A held NPC has no independent personal hand; contributions belong to its immediate holder.
        if (before.npcState == 2 && world.listRetainers().any { it.generalId == generalId }) {
            if (HwihaStratagemHand.META_KEY in before.meta) {
                val after = before.copy(meta = before.meta - HwihaStratagemHand.META_KEY)
                recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(after))
                world.applyGeneralDirtyFree(after)
            }
            return
        }
        val state=world.getState()
        val phase=HwihaPhase(state.currentYear,state.currentMonth,state.currentPhase)
        val current=HwihaStratagemHand.read(before.meta,generalId)
        val next=(current ?: HwihaStratagemHand.initial(generalId,phase))
            .withContributions(HwihaPersonDeckProjection.forHolder(world, generalId)).advance(phase)
        if(next===current)return
        val after=before.copy(meta=before.meta+(HwihaStratagemHand.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before),PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }
}
