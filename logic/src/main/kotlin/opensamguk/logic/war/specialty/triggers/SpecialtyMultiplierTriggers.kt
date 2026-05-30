package opensamguk.logic.war.specialty.triggers

import opensamguk.logic.war.WarUnit as ConcreteWarUnit
import opensamguk.logic.war.WarUnitGeneral
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit

/**
 * The AW3 (multiplier / trigger-injecting) war specialties' injected battle triggers — faithful ports of
 * the `WarUnitTrigger` PHP bodies at the EXACT documented priority. Same ownership rationale as
 * [SpecialtyInjectedTriggers]: in PHP the specialty constructs these (`che_저격::getBattlePhaseSkillTriggerList`
 * etc.), so A1 owns the injection leaves it needs. Draws come off the ONE shared `self.rng`; non-draw
 * side effects (logs, atmos clamps, bonus-phase bookkeeping) use the F1 contract + a downcast to the
 * concrete [ConcreteWarUnit]/[WarUnitGeneral] for methods the minimal trigger contract omits
 * (addBonusPhase / getMaxPhase / getCrewType / setWarPowerMultiply / deactivateSkill / state). G1 asserts
 * the full draw stream against A3's canonical `WarUnitTrigger` classes; this A1 copy carries the draw +
 * priority parity AW3 pins.
 */

/** PHP `che_저격시도.php` — PRE+100, ONE draw `nextBool(ratio)`. 저격 injects `(unit, TYPE_NONE, 0.5, 20, 40)`. */
class CheJeogyeokSido(
    unit: WarUnit,
    raiseType: Int = TYPE_NONE,
    private val ratio: Double = 0.5,
    private val woundMin: Double = 20.0,
    private val woundMax: Double = 40.0,
    private val addAtmos: Int = 20,
) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_PRE + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.isGeneral()) return true
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (oppose.getPhase() < 0) return true
        if (self.hasActivatedSkill("저격")) return true
        if (self.hasActivatedSkill("저격불가")) return true
        if (!self.rng.nextBool(ratio)) return true

        self.activateSkill("저격")
        selfEnv["저격발동자"] = raiseType
        selfEnv["woundMin"] = woundMin
        selfEnv["woundMax"] = woundMax
        selfEnv["addAtmos"] = addAtmos
        return true
    }
}

/** PHP `che_저격발동.php` — POST+100, 0–1 draws (POST injury `nextRangeInt(woundMin,woundMax)` only when oppose is a non-부상무효 general). */
class CheJeogyeokBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("저격")) return true
        if ((selfEnv["저격발동자"] as? Int ?: -1) != raiseType) return true
        if (selfEnv["저격발동"] == true) return true
        selfEnv["저격발동"] = true

        val opposeIsGeneral = oppose.isGeneral()
        if (opposeIsGeneral) {
            self.pushPlainActionLog("상대를 <C>저격</>했다!")
            self.pushBattleDetailLog("상대를 <C>저격</>했다!")
            oppose.pushPlainActionLog("상대에게 <R>저격</>당했다!")
            oppose.pushBattleDetailLog("상대에게 <R>저격</>당했다!")
        } else {
            self.pushPlainActionLog("성벽 수비대장을 <C>저격</>했다!")
            self.pushBattleDetailLog("성벽 수비대장을 <C>저격</>했다!")
        }

        // increaseVarWithLimit('atmos', addAtmos, 0, maxAtmosByWar) — non-draw atmos bump; deferred to A3's
        // canonical trigger (no public additive-atmos-with-limit mutator; zero draw-stream effect).

        // The POST injury draw — ONLY when oppose lacks 부상무효 AND oppose is a general (skip vs City).
        if (!oppose.hasActivatedSkill("부상무효") && oppose is WarUnitGeneral) {
            val woundMin = ((selfEnv["woundMin"] as? Number)?.toDouble() ?: 0.0).toInt()
            val woundMax = ((selfEnv["woundMax"] as? Number)?.toDouble() ?: 0.0).toInt()
            val wound = self.rng.nextRangeInt(woundMin, woundMax)
            oppose.state.increaseInjuryWithLimit(wound, 80)
        }

        processConsumableItem()
        return true
    }
}

/** PHP `che_격노시도.php` — BODY+400, 0–2 draws (branch on oppose 필살/회피; attacker nests a `nextBool(1/2)` 진노 draw). 격노 injects this. */
class CheGyeoknoSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BODY + 400

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!oppose.hasActivatedSkill("필살") && !oppose.hasActivatedSkill("회피")) return true
        if (self.hasActivatedSkill("격노불가")) return true

        val opposeConcrete = oppose as? ConcreteWarUnit
        if (oppose.hasActivatedSkill("필살")) {
            self.activateSkill("격노")
            opposeConcrete?.deactivateSkill("회피")
            if (self.isAttacker() && self.rng.nextBool(1.0 / 2)) {
                self.activateSkill("진노")
            }
        } else if (self.rng.nextBool(1.0 / 4)) {
            self.activateSkill("격노")
            opposeConcrete?.deactivateSkill("회피")
            if (self.isAttacker() && self.rng.nextBool(1.0 / 2)) {
                self.activateSkill("진노")
            }
        }
        return true
    }
}

/** PHP `che_격노발동.php` — POST+600, 1 indirect draw via `criticalDamage()`. 격노 injects this. */
class CheGyeoknoBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 600

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("격노")) return true

        val targetAct = if (oppose.hasActivatedSkill("필살")) "필살 공격" else "회피 시도"
        val isJinno = self.hasActivatedSkill("진노")
        val reaction = if (isJinno) "진노" else "격노"

        self.pushBattleDetailLog("상대의 ${targetAct}에 <C>${reaction}</>했다!</>")
        oppose.pushBattleDetailLog("${targetAct}에 상대가 <R>${reaction}</>했다!</>")

        if (isJinno) (self as? ConcreteWarUnit)?.addBonusPhase(1)
        self.multiplyWarPowerMultiply(self.criticalDamage())
        return true
    }
}

/** PHP `che_위압시도.php` — BEGIN+100, ZERO draws. 위압 injects this. */
class CheWiapSido(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_BEGIN + 100

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (self.getPhase() != 0 && oppose.getPhase() != 0) return true
        if (self.hasActivatedSkill("위압불가")) return true
        self.activateSkill("위압")
        oppose.activateSkill("회피불가", "필살불가", "계략불가")
        return true
    }
}

/** PHP `che_위압발동.php` — POST+700, ZERO draws. oppose setWarPowerMultiply(0) + atmos −5. 위압 injects this. */
class CheWiapBaldong(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 700

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (!self.hasActivatedSkill("위압")) return true

        oppose.pushBattleDetailLog("상대에게 <R>위압</>받았다!</>")
        self.pushBattleDetailLog("상대에게 <C>위압</>을 줬다!</>")
        oppose.setWarPowerMultiply(0.0)
        // PHP `che_위압발동.php:24`: `if($oppose instanceof WarUnitGeneral) $oppose->increaseVarWithLimit('atmos', -5, 40)`.
        // NOT cosmetic: the opponent's atmos 100→95 lowers their computeWarPower in every SUBSEQUENT phase
        // (warPower *= getComputedAtmos()), shifting per-phase damage — a battle-determinism parity fact
        // (G1b battle-01 post-state proves attacker atmos 95). Guard on a general unit (city has no atmos var).
        if (oppose.isGeneralUnit()) {
            oppose.decreaseAtmos(5, 40)
        }
        return true
    }
}

/** PHP `che_돌격지속.php` — POST+900, ZERO draws. attacker-only bonus-phase bookkeeping by attackCoef/phase. 돌격 injects this. */
class CheDolgyeokJisok(unit: WarUnit, raiseType: Int = TYPE_NONE) : BaseWarUnitTrigger(unit, raiseType) {
    override val priority: Int = ObjectTrigger.PRIORITY_POST + 900

    override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean {
        if (oppose.isCity()) return true
        if (!self.isAttacker()) return true

        val selfConcrete = self as? ConcreteWarUnit ?: return true
        val opposeConcrete = oppose as? ConcreteWarUnit ?: return true
        val attackCoef = selfConcrete.getCrewType().getAttackCoef(opposeConcrete.getCrewType())
        if (attackCoef < 1) {
            if (oppose.hasActivatedSkill("선제") && selfConcrete.getPhase() >= selfConcrete.getMaxPhase() - 2) {
                selfConcrete.addBonusPhase(-1)
            }
            return true
        }
        if (selfConcrete.getPhase() < selfConcrete.getMaxPhase() - 1) return true
        selfConcrete.addBonusPhase(1)
        return true
    }
}
