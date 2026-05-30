package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/WarActivateSkills.php:11-33`. Priority BEGIN+0. NO draws.
 * Pure flag setter: `isSelf` ⇒ `self.activateSkill(...skills)` else `oppose.activateSkill(...skills)`.
 */
class WarActivateSkills(
    unit: WarUnit,
    raiseType: Int,
    private val isSelf: Boolean,
    private vararg val activeSkills: String,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (isSelf) self.activateSkill(*activeSkills) else oppose.activateSkill(*activeSkills)
        return true
    }
}
