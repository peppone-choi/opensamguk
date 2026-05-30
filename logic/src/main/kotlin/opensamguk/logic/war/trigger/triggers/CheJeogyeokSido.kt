package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_저격시도.php:19-53`. Priority PRE+100. ctor (unit, raiseType,
 * ratio, woundMin, woundMax, addAtmos=20). General-only. Guards (NO draw): self & oppose both phase!=0 /
 * oppose.phase<0 / already '저격' / '저격불가'. Else ONE draw `nextBool(ratio)`; on hit set '저격' flag +
 * `selfEnv['저격발동자'/'woundMin'/'woundMax'/'addAtmos']` (the POST 저격발동 injury draw reads these).
 */
class CheJeogyeokSido(
    unit: WarUnit,
    raiseType: Int,
    private val ratio: Double,
    private val woundMin: Double,
    private val woundMax: Double,
    private val addAtmos: Int = 20,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (oppose.getPhase() < 0) return true
        if (self.hasActivatedSkill("저격")) return true
        if (self.hasActivatedSkill("저격불가")) return true
        if (!self.rng.nextBool(ratio)) return true

        self.activateSkill("저격")
        selfEnv["저격발동자"] = raiseType
        selfEnv["woundMin"] = woundMin
        selfEnv["woundMax"] = woundMax
        selfEnv["addAtmos"] = addAtmos
        return true
    }
}
