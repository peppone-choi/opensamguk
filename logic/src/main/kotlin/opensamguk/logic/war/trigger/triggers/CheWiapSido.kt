package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_위압시도.php:10-29`. Priority BEGIN+100. NO draws.
 * General-only. Guards: self & oppose both phase!=0 / self has '위압불가'. Else `activateSkill('위압')`
 * and force oppose `activateSkill('회피불가','필살불가','계략불가')`.
 */
class CheWiapSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (self.hasActivatedSkill("위압불가")) return true

        self.activateSkill("위압")
        oppose.activateSkill("회피불가", "필살불가", "계략불가")
        return true
    }
}
