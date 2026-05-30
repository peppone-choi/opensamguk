package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_약탈시도.php:17-46`. Priority PRE+400. ctor (unit, raiseType,
 * ratio, theftRatio). General-only. Guards (NO draw): self & oppose both phase!=0 / oppose not general /
 * already '약탈' / '약탈불가'. Else ONE draw `nextBool(ratio)`; on hit `activateSkill('약탈')` +
 * `selfEnv['theftRatio']` (the POST 약탈발동 reads it for the transfer).
 */
class CheYaktalSido(
    unit: WarUnit,
    raiseType: Int,
    private val ratio: Double,
    private val theftRatio: Double,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 400

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (!oppose.isGeneralUnit()) return true
        if (self.hasActivatedSkill("약탈")) return true
        if (self.hasActivatedSkill("약탈불가")) return true
        if (!self.rng.nextBool(ratio)) return true

        self.activateSkill("약탈")
        selfEnv["theftRatio"] = theftRatio
        return true
    }
}
