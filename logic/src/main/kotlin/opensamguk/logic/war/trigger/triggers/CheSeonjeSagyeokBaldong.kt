package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_선제사격발동.php:15-50`. Priority BEGIN+51. NO draws.
 * General-only. Guards: not '선제' / (oppose 선제 && oppose isAttacker → 맞선제는 공격자 처리). Else shift
 * both phases −1; if oppose also '선제' → mutual 2/3, else oppose×0 + self×2/3 with forced 불가 flags.
 */
class CheSeonjeSagyeokBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 51

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("선제")) return true
        if (oppose.hasActivatedSkill("선제") && oppose.isAttacker()) return true  // 맞선제라면 공격자가 처리

        self.addPhase(-1)
        oppose.addPhase(-1)
        if (oppose.hasActivatedSkill("선제")) {
            self.multiplyWarPowerMultiply(2.0 / 3.0)
            oppose.multiplyWarPowerMultiply(2.0 / 3.0)
            oppose.pushBattleDetailLog("서로 <C>선제 사격</>을 주고 받았다!</>")
            self.pushBattleDetailLog("서로 <C>선제 사격</>을 주고 받았다!</>")
            return true
        }

        oppose.multiplyWarPowerMultiply(0.0)
        self.multiplyWarPowerMultiply(2.0 / 3.0)
        self.activateSkill("회피불가", "필살불가", "계략불가")
        oppose.activateSkill("회피불가", "필살불가", "격노불가", "계략불가")

        oppose.pushBattleDetailLog("상대에게 <R>선제 사격</>을 받았다!</>")
        self.pushBattleDetailLog("상대에게 <C>선제 사격</>을 했다!</>")
        return true
    }
}
