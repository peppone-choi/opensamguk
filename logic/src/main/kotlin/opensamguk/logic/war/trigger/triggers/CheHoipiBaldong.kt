package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_회피발동.php`. Priority POST+500.
 * Guard: not '회피'. Else log + `oppose.multiplyWarPowerMultiply(1/6)` (the avoided strike is 1/6 power).
 */
class CheHoipiBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 500

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("회피")) return true

        oppose.pushBattleDetailLog("상대가 <R>회피</>했다!</>")
        self.pushBattleDetailLog("<C>회피</>했다!</>")

        oppose.multiplyWarPowerMultiply(1.0 / 6.0)
        return true
    }
}
