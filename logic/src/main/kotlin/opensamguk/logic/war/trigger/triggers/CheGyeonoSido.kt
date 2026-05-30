package opensamguk.logic.war.trigger.triggers

import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * Port target = PHP `WarUnitTrigger/che_격노시도.php:14-37`. Priority BODY+400. 0-2 draws.
 *
 * **DRAW ORDER (strict — the catalog §8 subtlety):**
 *   - guard (NO draw): oppose has NEITHER '필살' NOR '회피' → return; self has '격노불가' → return;
 *   - **oppose has '필살':** activate '격노', oppose `deactivateSkill('회피')`, then
 *     **if `isAttacker()` → DRAW `nextBool(1/2)`** (진노 roll) — NO trial draw on this branch;
 *   - **else (oppose has '회피'):** **DRAW `nextBool(1/4)` (trial);** on hit activate '격노',
 *     oppose `deactivateSkill('회피')`, then **if `isAttacker()` → DRAW `nextBool(1/2)`** (진노 roll).
 *
 * So: 필살 branch = 0 or 1 draw (진노 only when attacker); 회피 branch = 1 draw (trial) + optionally 1 (진노).
 */
class CheGyeonoSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BODY + 400

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!oppose.hasActivatedSkill("필살") && !oppose.hasActivatedSkill("회피")) return true
        if (self.hasActivatedSkill("격노불가")) return true

        if (oppose.hasActivatedSkill("필살")) {
            self.activateSkill("격노")
            oppose.deactivateSkill("회피")
            if (self.isAttacker() && self.rng.nextBool(1.0 / 2.0)) {
                self.activateSkill("진노")
            }
        } else if (self.rng.nextBool(1.0 / 4.0)) {
            self.activateSkill("격노")
            oppose.deactivateSkill("회피")
            if (self.isAttacker() && self.rng.nextBool(1.0 / 2.0)) {
                self.activateSkill("진노")
            }
        }
        return true
    }
}
