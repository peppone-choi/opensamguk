package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_부상무효.php:10-19`. Priority BEGIN+200. NO draws.
 * General-only. Unconditionally `activateSkill('부상무효')`. 견고 injects this with a distinct
 * `raiseType = TYPE_DEDUP_TYPE_BASE*404` (catalog §6) so its instance does NOT dedup-merge another source's.
 */
class CheBusangMuhyo(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 200

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        self.activateSkill("부상무효")
        return true
    }
}
