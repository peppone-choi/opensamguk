package opensamguk.logic.war.trigger.triggers

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_반계발동.php:15-33`. Priority POST+250. NO draws.
 * Guard: not '반계'. Else read `opposeEnv['magic'] = [magic, damage]`, log, and
 * `self.multiplyWarPowerMultiply(damage)` (the reflected magic damage applies to the 반계 caster).
 */
class CheBangyeBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 250

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("반계")) return true

        @Suppress("UNCHECKED_CAST")
        val magicTuple = opposeEnv["magic"] as List<Any?>
        val opposeMagic = magicTuple[0] as String
        val damage = (magicTuple[1] as Number).toDouble()

        val josaUl = JosaUtil.pick(opposeMagic, "을")
        self.pushBattleDetailLog("<C>반계</>로 상대의 <D>${opposeMagic}</>${josaUl} 되돌렸다!")
        oppose.pushBattleDetailLog("<D>${opposeMagic}</>${josaUl} <R>역으로</> 당했다!")

        self.multiplyWarPowerMultiply(damage)
        return true
    }
}
