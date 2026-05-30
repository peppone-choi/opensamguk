package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_선제사격시도.php:15-35`. Priority BEGIN+50. NO draws.
 * General-only. Guards (no draw): self & oppose both phase!=0 / already '선제' / 선제 on log.
 * Else `activateSkill('특수','선제')`.
 */
class CheSeonjeSagyeokSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 50

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (self.hasActivatedSkill("선제")) return true
        if (self.hasActivatedSkillOnLog("선제") > 0) return true

        self.activateSkill("특수", "선제")
        return true
    }
}
