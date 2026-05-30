package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_전멸시페이즈증가.php:15-23`. Priority POST+800. NO draws.
 * If self.phase!=0 AND oppose.phase==0 (the opponent was wiped this phase) → `addBonusPhase(1)` + the
 * 진격 battle-detail logs. Else no-op.
 */
class CheJeonmyeolsiPhaseJeunga(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 800

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() == 0) {
            self.addBonusPhase(1)
            self.pushBattleDetailLog("적군의 전멸에 <C>진격</>이 이어집니다!")
            oppose.pushBattleDetailLog("아군의 전멸에 상대의 <R>진격</>이 이어집니다!")
        }
        return true
    }
}
