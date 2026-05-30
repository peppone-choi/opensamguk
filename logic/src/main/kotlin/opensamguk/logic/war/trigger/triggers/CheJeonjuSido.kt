package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_저지시도.php:11-32`. Priority PRE+0 (최우선 PRE — fires first).
 * General-only. Guards (NO draw): attacker / has '특수' / has '저지불가'. Else ONE draw
 * `nextBool((atmos+train)/400)`; on hit `activateSkill('특수','저지')`.
 */
class CheJeonjuSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.isAttacker()) return true
        if (self.hasActivatedSkill("특수")) return true
        if (self.hasActivatedSkill("저지불가")) return true

        val ratio = self.getComputedAtmos() + self.getComputedTrain()
        if (self.rng.nextBool(ratio / 400)) {
            self.activateSkill("특수", "저지")
        }
        return true
    }
}
