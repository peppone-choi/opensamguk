package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_저격발동.php:19-56`. Priority POST+100. 0-1 draws.
 *
 * Guards (NO draw): not '저격' / `selfEnv['저격발동자'] !== raiseType` (the item/specialty raiseType match) /
 * already '저격발동'. Then `increaseVarWithLimit('atmos', addAtmos, 0, maxAtmosByWar)` (no draw).
 *
 * **The POST-resolution injury draw (catalog §8 #3):** `nextRangeInt(woundMin, woundMax)` fires ONLY when
 * the oppose LACKS '부상무효' AND the oppose is a general (a city has no injury) — `che_저격발동.php:49-50`.
 * Then `processConsumableItem()` (the item-side consume → flush-delta delete).
 */
class CheJeogyeokBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("저격")) return true
        if ((selfEnv["저격발동자"] ?: -1) != raiseType) return true
        if (selfEnv["저격발동"] == true) return true
        selfEnv["저격발동"] = true

        if (oppose.isGeneralUnit()) {
            self.pushPlainActionLog("상대를 <C>저격</>했다!")
            self.pushBattleDetailLog("상대를 <C>저격</>했다!")
            oppose.pushPlainActionLog("상대에게 <R>저격</>당했다!")
            oppose.pushBattleDetailLog("상대에게 <R>저격</>당했다!")
        } else {
            self.pushPlainActionLog("성벽 수비대장을 <C>저격</>했다!")
            self.pushBattleDetailLog("성벽 수비대장을 <C>저격</>했다!")
        }

        val addAtmos = (selfEnv["addAtmos"] as? Number)?.toInt() ?: 0
        self.increaseAtmos(addAtmos)

        if (!oppose.hasActivatedSkill("부상무효") && oppose.isGeneralUnit()) {
            val woundMin = (selfEnv["woundMin"] as Number).toInt()
            val woundMax = (selfEnv["woundMax"] as Number).toInt()
            oppose.increaseInjury(self.rng.nextRangeInt(woundMin, woundMax))
        }

        processConsumableItem()
        return true
    }
}
