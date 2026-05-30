package opensamguk.logic.war.trigger.triggers

import opensamguk.common.constants.GameUnitConst
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_궁병선제사격.php:16-60` (DEPRECATED — superseded by
 * 선제사격시도/발동; kept for legacy parity, not injected by the current crewType list). Priority BEGIN+50.
 * NO draws. Many early-return guards; on the shooting branch shifts both phases −1 and muls warpower
 * (2/3 mutual when oppose is also archer, else oppose×0 + self×2/3 with forced 불가 flags).
 */
class CheGungbyeongSeonjeSagyeok(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 50

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (self.hasActivatedSkill("선제")) return true
        if (self.hasActivatedSkillOnLog("선제") > 0) return true
        if (oppose.hasActivatedSkill("선제")) return true  // 맞 선제는 한쪽에서 같이 처리

        self.activateSkill("특수", "선제")
        self.addPhase(-1)
        oppose.addPhase(-1)
        if (oppose.getCrewType().armType == GameUnitConst.T_ARCHER) {
            oppose.activateSkill("특수", "선제")
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
