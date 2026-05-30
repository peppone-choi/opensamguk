package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_약탈발동.php:19-55`. Priority POST+350. NO draws.
 * Guards: not '약탈' / already '약탈발동' / oppose not general. Else transfer `theftRatio` of the oppose's
 * gold/rice via [WarUnit.stealFrom] (oppose −clamped, self +), log the stolen amounts, `processConsumableItem()`.
 */
class CheYaktalBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 350

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("약탈")) return true
        if (selfEnv["약탈발동"] == true) return true
        selfEnv["약탈발동"] = true

        if (!oppose.isGeneralUnit()) return true

        val theftRatio = (selfEnv["theftRatio"] as Number).toDouble()
        val (theftGold, theftRice) = self.stealFrom(oppose, theftRatio)

        self.pushPlainActionLog("상대를 <C>약탈</>했다!")
        self.pushBattleDetailLog("상대에게서 금 ${theftGold}, 쌀 ${theftRice} 만큼을 <C>약탈</>했다!")
        oppose.pushPlainActionLog("상대에게 <R>약탈</>당했다!")
        oppose.pushBattleDetailLog("상대에게 금 ${theftGold}, 쌀 ${theftRice} 만큼을 <R>약탈</>당했다!")

        processConsumableItem()
        return true
    }
}
