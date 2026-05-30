package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_돌격지속.php:15-35`. Priority POST+900. NO draws.
 * Guards: oppose is a city / self not attacker → return. Then by crew attackCoef:
 *  - attackCoef < 1: if oppose '선제' AND `phase >= maxPhase-2` → `addBonusPhase(-1)`; return;
 *  - attackCoef >= 1 AND `phase >= maxPhase-1` → `addBonusPhase(1)` (the 돌격 specialty's lingering charge).
 */
class CheDolgyeokJisok(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 900

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (oppose.isCity()) return true
        if (!self.isAttacker()) return true

        val attackCoef = self.getCrewType().getAttackCoef(oppose.getCrewType())
        if (attackCoef < 1) {
            if (oppose.hasActivatedSkill("선제") && self.getPhase() >= self.getMaxPhase() - 2) {
                self.addBonusPhase(-1)
            }
            return true
        }
        if (self.getPhase() < self.getMaxPhase() - 1) {
            return true
        }
        self.addBonusPhase(1)
        return true
    }
}
