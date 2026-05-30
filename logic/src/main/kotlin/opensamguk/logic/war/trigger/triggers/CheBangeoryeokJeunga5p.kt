package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_방어력증가5p.php:11-20`. Priority FINAL+200 (최후미). NO draws.
 * The defender's +5% defence: if self is the defender → oppose `multiplyWarPowerMultiply(1/1.05)`.
 * Attacker → no-op.
 */
class CheBangeoryeokJeunga5p(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_FINAL + 200

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.isAttacker()) {
            oppose.multiplyWarPowerMultiply(1.0 / 1.05)
        }
        return true
    }
}
