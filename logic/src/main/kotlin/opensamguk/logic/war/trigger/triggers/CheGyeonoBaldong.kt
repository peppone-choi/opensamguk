package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_격노발동.php:15-35`. Priority POST+600. 1 (indirect) draw.
 * Guard (NO draw): not '격노'. Else log (필살/회피 + 격노/진노 by skill state); if '진노' `addBonusPhase(1)`
 * (no draw); then `multiplyWarPowerMultiply(criticalDamage())` — `criticalDamage()` = 1 `nextRange` draw.
 */
class CheGyeonoBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 600

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("격노")) return true

        val targetAct = if (oppose.hasActivatedSkill("필살")) "필살 공격" else "회피 시도"
        val isJinno = self.hasActivatedSkill("진노")
        val reaction = if (isJinno) "진노" else "격노"

        self.pushBattleDetailLog("상대의 ${targetAct}에 <C>${reaction}</>했다!</>")
        oppose.pushBattleDetailLog("${targetAct}에 상대가 <R>${reaction}</>했다!</>")

        if (isJinno) {
            self.addBonusPhase(1)
        }
        self.multiplyWarPowerMultiply(self.criticalDamage())
        return true
    }
}
