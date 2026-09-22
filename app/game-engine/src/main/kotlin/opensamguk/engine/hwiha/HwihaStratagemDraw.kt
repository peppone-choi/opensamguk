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
        val state=world.getState()
        val phase=HwihaPhase(state.currentYear,state.currentMonth,state.currentPhase)
        val current=HwihaStratagemHand.read(before.meta,generalId)
        val next=current?.advance(phase) ?: HwihaStratagemHand.initial(generalId,phase)
        if(next===current)return
        val after=before.copy(meta=before.meta+(HwihaStratagemHand.META_KEY to next.toMetaValue()))
        recorder.diffGeneral(PerTurnOverlay.toLogicGeneral(before),PerTurnOverlay.toLogicGeneral(after))
        world.applyGeneralDirtyFree(after)
    }
}
