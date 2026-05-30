package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_필살강화_회피불가.php:11-21`. Priority PRE+150. NO draws.
 * If self has '필살' → force oppose `activateSkill('회피불가')` (필살 specialty injects this so a 필살 hit
 * cannot be avoided). Else no-op.
 */
class CheFilsalGanghwaHoipiBulga(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 150

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("필살")) return true
        oppose.activateSkill("회피불가")
        return true
    }
}
