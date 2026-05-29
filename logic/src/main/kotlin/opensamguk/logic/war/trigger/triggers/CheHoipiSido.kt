package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_회피시도.php`. Priority PRE+200.
 * Guards (NO draw): not-General / '특수' / '회피불가'. Else ONE draw `nextBool(getComputedAvoidRatio())`;
 * on hit `activateSkill('회피시도','회피')`.
 */
class CheHoipiSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 200

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.isGeneral()) return true
        if (self.hasActivatedSkill("특수")) return true
        if (self.hasActivatedSkill("회피불가")) return true

        if (!self.rng.nextBool(self.getComputedAvoidRatio())) return true

        self.activateSkill("회피시도", "회피")
        return true
    }
}
