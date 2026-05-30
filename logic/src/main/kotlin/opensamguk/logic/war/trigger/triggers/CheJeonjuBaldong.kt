package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_저지발동.php:14-51`. Priority POST+0 (최우선 POST — fires before
 * every other POST). NO draws.
 *
 * **The `stopNextAction` halt (catalog §8 #2):** on a '저지' hit this `return false` → the
 * [BaseWarUnitTrigger.action] wrapper sets `env['stopNextAction']`, so EVERY later-priority trigger this
 * phase early-returns with ZERO draws. A naive port that keeps firing after 저지 over-draws and desyncs.
 *
 * Side-effects (NO draw): shift both phases −1; if `phase < maxPhase` oppose `addBonusPhase(-1)`; the
 * dex/exp/rice block via [WarUnit.applyBlockReward]; zero BOTH warpower multipliers.
 */
class CheJeonjuBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("저지")) return true
        if (selfEnv["저지발동"] == true) return true
        selfEnv["저지발동"] = true

        self.addPhase(-1)
        oppose.addPhase(-1)
        if (self.getPhase() < self.getMaxPhase()) {
            oppose.addBonusPhase(-1)
        }

        self.pushBattleDetailLog("상대를 <C>저지</>했다!")
        oppose.pushBattleDetailLog("저지</>당했다!")

        // PHP :36-45: dex(oppose & self) + levelExp + 0.25× rice spend, all keyed off oppose.getWarPower()*0.9.
        self.applyBlockReward(oppose)

        self.setWarPowerMultiply(0.0)
        oppose.setWarPowerMultiply(0.0)

        return false  // 저지는 모든 이벤트를 중지시킨다.
    }
}
