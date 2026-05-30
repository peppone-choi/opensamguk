package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_위압발동.php:15-28`. Priority POST+700. NO draws.
 * Guard: not '위압'. Else log, oppose `setWarPowerMultiply(0)` (the oppose's strike is fully nullified —
 * but calcDamage STILL draws `nextRange(0.9,1.1)`, the product is just 0), and if oppose is a general
 * `increaseVarWithLimit('atmos', -5, 40)` via [WarUnit.decreaseAtmos].
 */
class CheWiapBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 700

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("위압")) return true

        oppose.pushBattleDetailLog("상대에게 <R>위압</>받았다!</>")
        self.pushBattleDetailLog("상대에게 <C>위압</>을 줬다!</>")
        oppose.setWarPowerMultiply(0.0)
        if (oppose.isGeneralUnit()) {
            oppose.decreaseAtmos(5, 40)
        }
        return true
    }
}
