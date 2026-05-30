package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_반계시도.php:11-39`. Priority BODY+300. ctor (unit, raiseType=0,
 * prob=0.4). Guards (NO draw): oppose lacks '계략' / self has '반계불가'. Else ONE draw `nextBool(prob)`;
 * on hit `assert opposeEnv['magic']`, `activateSkill('반계')`, oppose `deactivateSkill('계략')`.
 */
class CheBangyeSido(
    unit: WarUnit,
    raiseType: Int = TYPE_NONE,
    private val prob: Double = 0.4,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BODY + 300

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!oppose.hasActivatedSkill("계략")) return true
        if (self.hasActivatedSkill("반계불가")) return true
        if (!self.rng.nextBool(prob)) return true

        require(opposeEnv.containsKey("magic"))
        self.activateSkill("반계")
        oppose.deactivateSkill("계략")
        return true
    }
}
