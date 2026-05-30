package opensamguk.logic.war.specialty.triggers

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.war.WarUnit as ConcreteWarUnit
import opensamguk.logic.war.WarUnitGeneral
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * The concrete battle triggers that the **war specialties (A1)** inject through their
 * `getBattleInitSkillTriggerList` / `getBattlePhaseSkillTriggerList` hooks (PHP `ActionSpecialWar/che_*.php`
 * `new che_*($unit)` inside those hooks). These live in A1's `war/specialty/triggers/` package — disjoint
 * from A3's `war/trigger/triggers/` catalog — because in PHP the SPECIALTY is the thing that constructs the
 * trigger instance, so A1 owns the injection wiring + the injected leaves it needs.
 *
 * Each is a faithful port of its `WarUnitTrigger` PHP body at the EXACT documented priority. Draws come
 * off the ONE shared `self.rng` (the F1 contract surface) in the documented order; non-draw skill-flag /
 * warpower-multiply / battle-log side effects use the [WarUnit] contract (with a downcast to the concrete
 * [ConcreteWarUnit] for `deactivateSkill`, which the F1 contract does not expose). The 발동 (activation)
 * leaves' purely-cosmetic `setVar('injury', 0)` reset is NOT modeled here (no draw effect; the immutable
 * [opensamguk.logic.domain.General] has no setVar) — G1 asserts the full draw stream against A3's canonical
 * `WarUnitTrigger/` classes, this A1 copy carries the draw + priority parity AW2/AW3 pin.
 */

/** PHP `WarUnitTrigger/che_필살강화_회피불가.php` — PRE+150, ZERO draws (pure flag). 필살 injects this. */
class CheFilsalGanghwaHoipibulga(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 150

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("필살")) return true
        oppose.activateSkill("회피불가")
        return true
    }
}

/** PHP `WarUnitTrigger/che_부상무효.php` — BEGIN+200, ZERO draws (pure flag). 견고 injects this with raiseType DEDUP*404. */
class CheBusangMuhyo(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 200

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        // PHP `assert($self instanceof WarUnitGeneral)`; non-general is a no-op flag here.
        self.activateSkill("부상무효")
        return true
    }
}

/** PHP `WarUnitTrigger/che_전투치료시도.php` — PRE+350, ONE draw `nextBool(0.4)`. 의술 injects this. */
class CheJeontuChiryoSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 350

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.hasActivatedSkill("치료")) return true
        if (self.hasActivatedSkill("치료불가")) return true
        if (!self.rng.nextBool(0.4)) return true
        self.activateSkill("치료")
        return true
    }
}

/** PHP `WarUnitTrigger/che_전투치료발동.php` — POST+550, ZERO draws. oppose ×0.7; (injury reset non-draw, deferred). */
class CheJeontuChiryoBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 550

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("치료")) return true
        if (selfEnv["치료발동"] == true) return true
        selfEnv["치료발동"] = true

        oppose.pushBattleDetailLog("상대가 <R>치료</>했다!")
        self.pushBattleDetailLog("<C>치료</>했다!")

        oppose.multiplyWarPowerMultiply(0.7)
        // self.getGeneral().setVar('injury', 0) — non-draw cosmetic reset; immutable General has no setVar (A3/G1).

        processConsumableItem()
        return true
    }
}

/** PHP `WarUnitTrigger/che_반계시도.php` — BODY+300, ONE draw `nextBool(prob=0.4)`. 반계 injects this. */
class CheBangyeSido(unit: WarUnit, raiseType: Int = TYPE_NONE, private val prob: Double = 0.4) :
    BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BODY + 300

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!oppose.hasActivatedSkill("계략")) return true
        if (self.hasActivatedSkill("반계불가")) return true
        if (!self.rng.nextBool(prob)) return true
        // PHP `assert(key_exists('magic', $opposeEnv))`.
        self.activateSkill("반계")
        (oppose as? ConcreteWarUnit)?.deactivateSkill("계략")
        return true
    }
}

/** PHP `WarUnitTrigger/che_반계발동.php` — POST+250, ZERO draws. self ×= opposeEnv['magic'] damage. 반계 injects this. */
class CheBangyeBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 250

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("반계")) return true

        @Suppress("UNCHECKED_CAST")
        val magicPayload = opposeEnv["magic"] as? Pair<String, Double> ?: return true
        val (opposeMagic, damage) = magicPayload
        val josaUl = JosaUtil.pick(opposeMagic, "을")

        self.pushBattleDetailLog("<C>반계</>로 상대의 <D>${opposeMagic}</>${josaUl} 되돌렸다!")
        oppose.pushBattleDetailLog("<D>${opposeMagic}</>${josaUl} <R>역으로</> 당했다!")

        self.multiplyWarPowerMultiply(damage)
        return true
    }
}
