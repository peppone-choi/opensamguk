package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_필살발동.php`. Priority POST+400.
 * Guard: not '필살' / already-fired (`selfEnv['필살발동']`). Else log + `multiplyWarPowerMultiply(criticalDamage())`
 * — `criticalDamage()` draws `nextRange(criticalDamageRange)` inside the unit (the POST+400 crit draw).
 */
class CheFilsalBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 400

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("필살")) return true
        if (selfEnv["필살발동"] == true) return true
        selfEnv["필살발동"] = true

        oppose.pushBattleDetailLog("상대의 <R>필살</>공격!</>")
        self.pushBattleDetailLog("<C>필살</>공격!</>")

        self.multiplyWarPowerMultiply(self.criticalDamage())
        return true
    }
}
