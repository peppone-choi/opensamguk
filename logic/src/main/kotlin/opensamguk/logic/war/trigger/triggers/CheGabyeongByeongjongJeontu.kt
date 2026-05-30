package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_기병병종전투.php:11-29`. Priority FINAL+100 (최후미). NO draws.
 * Cavalry crew-type warpower nudge by side:
 *  - self is defender → oppose ×1.02, self ×0.97;
 *  - self is attacker vs a city → self ×0.9;
 *  - self is attacker vs a general → oppose ×0.97, self ×1.02.
 */
class CheGabyeongByeongjongJeontu(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_FINAL + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.isAttacker()) {
            oppose.multiplyWarPowerMultiply(1.02)
            self.multiplyWarPowerMultiply(0.97)
        } else if (oppose.isCity()) {
            self.multiplyWarPowerMultiply(0.9)
        } else {
            oppose.multiplyWarPowerMultiply(0.97)
            self.multiplyWarPowerMultiply(1.02)
        }
        return true
    }
}
