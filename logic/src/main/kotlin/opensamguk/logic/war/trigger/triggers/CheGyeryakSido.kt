package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_계략시도.php`. Priority PRE+300 — the most draw-dense site.
 *
 * **DRAW ORDER (strict):**
 *   - step-0 `if(hasActivatedSkill('계략불가')) return true` — ZERO draws, AHEAD of every prob compute (`:33-35`);
 *   - `prob = getMagicTrialProb(oppose)` (NO draw; intel·magicCoef + warMagicTrialProb fold + phase-0 ×3);
 *   - `if (prob <= 0) return true` — NO draw (`:43`);
 *   - **DRAW 1** `nextBool(prob)` — fail ⇒ return (`:57`);
 *   - `succProb = getMagicSuccessProb(oppose)` (NO draw; 0.7 + warMagicSuccessProb fold);
 *   - table by `oppose.isCity()`: city `{급습,위보,혼란}` vs general `{위보,매복,반목,화계,혼란}` (LITERAL insertion order);
 *   - **DRAW 2** `choice(array_keys(table))` (`:66/70`);
 *   - `successDamage = foldMagicSuccessDamage(...)` (NO draw; warMagicSuccessDamage, success branch only `:74-75`);
 *   - `activateSkill('계략시도', magic)`;
 *   - **DRAW 3** `nextBool(succProb)` (`:79`) ⇒ '계략' store `[magic, successDamage]` else '계략실패' store RAW `[magic, failDamage]`.
 */
class CheGyeryakSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 300

    companion object {
        // [successDamage, failDamage] keyed by magic name. INSERTION order = the choice() key order (parity).
        val TABLE_TO_GENERAL: LinkedHashMap<String, Pair<Double, Double>> = linkedMapOf(
            "위보" to (1.2 to 1.1),
            "매복" to (1.4 to 1.2),
            "반목" to (1.6 to 1.3),
            "화계" to (1.8 to 1.4),
            "혼란" to (2.0 to 1.5),
        )
        val TABLE_TO_CITY: LinkedHashMap<String, Pair<Double, Double>> = linkedMapOf(
            "급습" to (1.2 to 1.1),
            "위보" to (1.4 to 1.2),
            "혼란" to (1.6 to 1.3),
        )
    }

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        // PHP asserts $self instanceof WarUnitGeneral (계략시도 is only seeded onto generals).
        if (self.hasActivatedSkill("계략불가")) return true

        val magicTrialProb = self.getMagicTrialProb(oppose)
        if (magicTrialProb <= 0) return true

        // DRAW 1
        if (!self.rng.nextBool(magicTrialProb)) return true

        val magicSuccessProb = self.getMagicSuccessProb(oppose)

        val table = if (oppose.isCity()) TABLE_TO_CITY else TABLE_TO_GENERAL
        // DRAW 2
        val magic = self.rng.choice(table.keys.toList())
        val (rawSuccess, failDamage) = table.getValue(magic)
        val successDamage = self.foldMagicSuccessDamage(oppose, magic, rawSuccess)

        self.activateSkill("계략시도", magic)
        // DRAW 3
        if (self.rng.nextBool(magicSuccessProb)) {
            self.activateSkill("계략")
            selfEnv["magic"] = listOf(magic, successDamage)
        } else {
            self.activateSkill("계략실패")
            selfEnv["magic"] = listOf(magic, failDamage)
        }
        return true
    }
}
