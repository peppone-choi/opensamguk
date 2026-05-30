package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_성벽부상무효.php:10-23`. Priority BEGIN+150. NO draws.
 * General-only. Activates '부상무효' ONLY when the oppose is a city (`$oppose instanceof WarUnitCity`).
 */
class CheSeongbyeokBusangMuhyo(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 150

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!oppose.isCity()) return true
        self.activateSkill("부상무효")
        return true
    }
}
