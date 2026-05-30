package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/전투력보정.php:10-24`. Priority BEGIN+20. NO draws.
 * Pure: `self.multiplyWarPowerMultiply(attMul)`, `oppose.multiplyWarPowerMultiply(defMul)`,
 * then `processConsumableItem()`.
 */
class JeontuRyeokBojeong(
    unit: WarUnit,
    private val attackerWarPowerMultiplier: Double,
    private val defenderWarPowerMultiplier: Double = 1.0,
    raiseType: Int = TYPE_NONE,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 20

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        self.multiplyWarPowerMultiply(attackerWarPowerMultiplier)
        oppose.multiplyWarPowerMultiply(defenderWarPowerMultiplier)
        processConsumableItem()
        return true
    }
}
