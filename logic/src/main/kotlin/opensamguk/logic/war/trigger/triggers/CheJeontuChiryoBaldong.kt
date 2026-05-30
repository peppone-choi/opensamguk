package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_전투치료발동.php:15-34`. Priority POST+550. NO draws.
 * Guards: not '치료' / already '치료발동'. Else log, oppose `multiplyWarPowerMultiply(0.7)`, self injury→0
 * via [WarUnit.clearInjury], then `processConsumableItem()` (item-side consume → flush-delta delete).
 */
class CheJeontuChiryoBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 550

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("치료")) return true
        if (selfEnv["치료발동"] == true) return true
        selfEnv["치료발동"] = true

        oppose.pushBattleDetailLog("상대가 <R>치료</>했다!")
        self.pushBattleDetailLog("<C>치료</>했다!")

        oppose.multiplyWarPowerMultiply(0.7)
        self.clearInjury()

        processConsumableItem()
        return true
    }
}
