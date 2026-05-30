package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_퇴각부상무효.php:10-19`. Priority BEGIN+300. NO draws.
 * General-only. Unconditionally `activateSkill('퇴각부상무효')` (tryWound short-circuits on the log-flag).
 */
class CheToegakBusangMuhyo(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 300

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        self.activateSkill("퇴각부상무효")
        return true
    }
}
