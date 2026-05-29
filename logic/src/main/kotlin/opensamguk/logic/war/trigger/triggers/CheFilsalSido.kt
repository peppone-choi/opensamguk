package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_필살시도.php`. Priority PRE+120.
 * Guards (NO draw): not-General / '특수' / '필살불가'. Else ONE draw `nextBool(getComputedCriticalRatio())`;
 * on hit `activateSkill('필살시도','필살')`.
 */
class CheFilsalSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 120

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.isGeneral()) return true
        if (self.hasActivatedSkill("특수")) return true
        if (self.hasActivatedSkill("필살불가")) return true

        if (!self.rng.nextBool(self.getComputedCriticalRatio())) return true

        self.activateSkill("필살시도", "필살")
        return true
    }
}
