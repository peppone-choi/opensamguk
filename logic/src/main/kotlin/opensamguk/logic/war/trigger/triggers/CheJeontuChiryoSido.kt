package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_전투치료시도.php:11-30`. Priority PRE+350. General-only.
 * Guards (NO draw): already '치료' / '치료불가'. Else ONE draw `nextBool(0.4)`; on hit `activateSkill('치료')`.
 */
class CheJeontuChiryoSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 350

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.hasActivatedSkill("치료")) return true
        if (self.hasActivatedSkill("치료불가")) return true
        if (!self.rng.nextBool(0.4)) return true

        self.activateSkill("치료")
        return true
    }
}
