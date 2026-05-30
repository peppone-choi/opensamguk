package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/event_충차아이템소모.php:14-46`. Priority PRE+200 (ties with 회피시도;
 * insertion order breaks the tie). NO draws (pure siege-item bookkeeping; the consumable consumption is
 * via [processConsumableItem], not an RNG draw).
 *
 * Two paths:
 *  - if already '충차공격' on log AND at the LAST phase (`getMaxPhase()-1`) AND remaining ≤ 0 →
 *    [processConsumableItem] (consume the ram), return;
 *  - else, when self is a general attacking a city and has NOT logged '충차공격' → push the wall-attack log,
 *    `activateSkill('충차공격')`, and decrement the per-general remaining-ram aux counter.
 */
class EventChungchaItemConsume(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 200

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.hasActivatedSkillOnLog("충차공격") > 0 && self.getPhase() == self.getMaxPhase() - 1) {
            val remain = self.getSiegeRamRemain()
            if (remain <= 0) {
                processConsumableItem()
            }
            return true
        }

        if (!self.isGeneralUnit()) return true
        if (!oppose.isCity()) return true
        if (self.hasActivatedSkillOnLog("충차공격") > 0) return true

        self.pushBattleDetailLog("<C>충차</>로 성벽을 공격합니다.")
        val remain = self.getSiegeRamRemain()
        self.activateSkill("충차공격")
        self.setSiegeRamRemain(remain - 1)
        return true
    }
}
