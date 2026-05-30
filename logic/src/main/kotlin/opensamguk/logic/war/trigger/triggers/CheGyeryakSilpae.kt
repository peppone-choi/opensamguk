package opensamguk.logic.war.trigger.triggers

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_계략실패.php`. Priority POST+300 (shares the bucket with 계략발동;
 * fires AFTER it by seed arg order). NO draws — pure stat fold + log + multiply.
 * Guard: not '계략실패' / already-fired (`selfEnv['계략실패']`). Reads `selfEnv['magic']=[magic,damage]`,
 * applies `warMagicFailDamage` fold, logs, `multiplyWarPowerMultiply(1/damage)` + `oppose.multiplyWarPowerMultiply(damage)`.
 */
class CheGyeryakSilpae(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 300

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("계략실패")) return true
        if (selfEnv["계략실패"] == true) return true
        selfEnv["계략실패"] = true

        @Suppress("UNCHECKED_CAST")
        val magicTuple = selfEnv["magic"] as List<Any?>
        val magic = magicTuple[0] as String
        var damage = (magicTuple[1] as Number).toDouble()

        damage = self.foldMagicFailDamage(oppose, magic, damage)
        val josaUl = JosaUtil.pick(magic, "을")

        self.pushBattleDetailLog("<D>${magic}</>${josaUl} <R>실패</>했다!")
        oppose.pushBattleDetailLog("<D>${magic}</>${josaUl} 간파했다!")

        self.multiplyWarPowerMultiply(1.0 / damage)
        oppose.multiplyWarPowerMultiply(damage)
        return true
    }
}
