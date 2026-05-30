package opensamguk.logic.war.trigger

import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.RandUtil

/**
 * The trigger-facing contract a battle unit exposes to [BaseWarUnitTrigger] and the base-7 phase triggers.
 *
 * **Ownership / seam (F1):** this interface is the SHARED extension point between the F1 trigger registry
 * and the F2 concrete units. F1 owns ONLY this trigger-facing surface; **F2 creates the concrete
 * `WarUnit`/`WarUnitGeneral`/`WarUnitCity` and makes them IMPLEMENT this interface** (it does NOT re-declare
 * it). This mirrors the core2026 oracle, where the `WarUnit` interface is imported by the triggers and the
 * concrete units in `units/` implement it — so the registry's `fire()` draw order depends on the contract,
 * never on the concrete unit.
 *
 * Carries ONLY what the registry + base triggers need; the full power-formula / HP / finishBattle surface
 * lives on the F2 `WarUnit` base and is NOT widened here.
 */
interface WarUnit {
    /** The ONE shared `RandUtil(LiteHashDrbg(warSeed))` — threaded by reference into every unit (NEVER re-seeded). */
    val rng: RandUtil

    /** Stable per-unit id (the PHP `spl_object_id` substitute used in [ObjectTrigger.getUniqueID]). */
    fun getUnitId(): String

    /** Attacker side? Drives the [BaseWarUnitTrigger] e_attacker/e_defender env split. */
    fun isAttacker(): Boolean

    /** Current phase index (계략 phase-0 ×3 magic boost reads this). */
    fun getPhase(): Int

    fun hasActivatedSkill(skillName: String): Boolean

    /** PHP `activateSkill(...$skillNames)` — variadic. */
    fun activateSkill(vararg skillNames: String)

    /** PHP `multiplyWarPowerMultiply($multiply)`. */
    fun multiplyWarPowerMultiply(multiply: Double)

    // --- A3 extension: the trigger-facing surface the 29 BEGIN/PRE/BODY/POST/FINAL triggers need ----------
    // F1 owns this contract; A3 (the SOLE Tier-1 family touching war/trigger/) widens it for the catalog
    // leaves. The concrete F2 WarUnitGeneral/WarUnitCity (war/WarUnit*.kt, untouched by A1/A4) implement
    // these — they delegate the DRAW-free var/log/dex side-effects so the trigger bodies stay a faithful
    // 1:1 transcription of the PHP grand truth while the draws (nextBool/choice/nextRangeInt) stay inline.

    /** PHP `getComputedAtmos()` — 저지시도's `ratio = atmos + train`. CROSS-folded inside the impl. */
    fun getComputedAtmos(): Double

    /** PHP `getComputedTrain()` — 저지시도's `ratio = atmos + train`. */
    fun getComputedTrain(): Double

    /** PHP `hasActivatedSkillOnLog($name)` = logCount + (currently-active ? 1 : 0). 선제/충차 gate. */
    fun hasActivatedSkillOnLog(skillName: String): Int

    /** PHP `deactivateSkill(...$skillNames)`. 반계시도 (`계략` off), 격노시도 (`회피` off). */
    fun deactivateSkill(vararg skillNames: String)

    /** PHP `getPhase()`/`getMaxPhase()` phase shifters (선제발동/저지발동/돌격지속/충차). */
    fun getMaxPhase(): Int

    /** PHP `addPhase($n)` — 선제발동/저지발동 shift both sides −1. */
    fun addPhase(phase: Int)

    /** PHP `addBonusPhase($n)` — 격노발동(진노)/전멸시페이즈증가/돌격지속/저지발동. */
    fun addBonusPhase(cnt: Int)

    /** PHP `setWarPowerMultiply($v=1.0)` — 위압발동(0)/저지발동(0). */
    fun setWarPowerMultiply(multiply: Double)

    /** PHP `getWarPower()` = warPower × warPowerMultiply — 저지발동's `oppose.getWarPower()*0.9`. */
    fun getWarPower(): Double

    /** PHP `getCrewType()` — 선제(armType==T_ARCHER)/돌격(getAttackCoef)/기병/방어력 crew-type triggers. */
    fun getCrewType(): GameUnitDetail

    /** True ⇔ this unit is a [WarUnitGeneral] body delegate is safe (能力치/약탈/저격/치료/저지 reach getGeneral()). */
    fun isGeneralUnit(): Boolean

    // --- DRAW-free var/dex/exp side-effect delegates (the impl routes these to its mutable state holder) --

    /** PHP 능력치변경 `setVar/increaseVarWithLimit/multiplyVarWithLimit` by operator. */
    fun applyVarChange(variable: String, operator: String, value: Double, limitMin: Double?, limitMax: Double?)

    /** PHP 저격발동 `increaseVarWithLimit('atmos', addAtmos, 0, maxAtmosByWar)`. */
    fun increaseAtmos(delta: Int)

    /** PHP 저격발동 `oppose.getGeneral().increaseVarWithLimit('injury', amount, null, 80)`. */
    fun increaseInjury(amount: Int)

    /** PHP 위압발동 `oppose.increaseVarWithLimit('atmos', -5, 40)` (general oppose only). */
    fun decreaseAtmos(delta: Int, min: Int)

    /** PHP 약탈발동 transfer: returns `getVar('gold'|'rice') * ratio` of the oppose, applied as deltas. */
    fun stealFrom(oppose: WarUnit, theftRatio: Double): Pair<Double, Double>

    /** PHP 전투치료발동 `self.getGeneral().setVar('injury', 0)`. */
    fun clearInjury()

    /** PHP 저지발동 `addDex(crewType, exp)` + `addLevelExp` + `calcRiceConsumption`-driven rice spend. */
    fun applyBlockReward(oppose: WarUnit)

    /** PHP 충차아이템소모 `getAuxVar(REMAIN_KEY) ?? 0` and `setAuxVar(REMAIN_KEY, remain-1)`. */
    fun getSiegeRamRemain(): Int
    fun setSiegeRamRemain(value: Int)

    /** PHP `criticalDamage()` = `nextRange(criticalDamageRange)`. The draw fires inside the F2 impl. */
    fun criticalDamage(): Double

    /** PHP `getComputedCriticalRatio()` — the 필살시도 probability. */
    fun getComputedCriticalRatio(): Double

    /** PHP `getComputedAvoidRatio()` — the 회피시도 probability. */
    fun getComputedAvoidRatio(): Double

    /** PHP `getLogger()->pushGeneralBattleDetailLog(msg, PLAIN)` — the `<Y1>【name】</>` battle-detail line. */
    fun pushBattleDetailLog(message: String)

    /** PHP `getLogger()->pushGeneralActionLog(msg, PLAIN)` — the '사용!' consumable line. */
    fun pushPlainActionLog(message: String)

    /** Display name of the held item (the `<C>{name}</>` tag). */
    fun getItemName(): String

    /** Raw name of the held item (the Josa pick subject). */
    fun getItemRawName(): String

    /**
     * Consume the held item. **MUST record a flush-delta (ChangeRecorder), NEVER an inline DB write**
     * (the F2/engine impl routes this through the recorder). PHP `General.deleteItem()`.
     */
    fun deleteItem()

    // --- magic (계략) phase delegates — the NON-draw stat computation the magic trigger needs ---------------
    // The DRAWS (nextBool/choice/nextBool) live in the trigger (the F1 draw-order parity surface); the
    // probability/damage COMPUTATION reaches into General/crewType/the onCalcStat pipeline (F2/F5/A1) so it is
    // delegated here. F2/A1 fill these faithfully; G1 asserts the resulting draw stream byte-for-byte.

    /** True for a WarUnitGeneral (필살/회피/계략 require a general self). PHP `$self instanceof WarUnitGeneral`. */
    fun isGeneral(): Boolean

    /** True for a WarUnitCity (magic table selection: city `{급습,위보,혼란}` vs general `{위보,…}`). */
    fun isCity(): Boolean

    /**
     * The FULLY-computed magic trial probability (NO draw): `intel/100 * crewType.magicCoef`, the
     * `warMagicTrialProb` owner-then-oppose cross-fold, AND the phase-0 `rawIntel*3>=allStat ⇒ ×3` boost.
     * The `≤0 ⇒ no draw` short-circuit is applied in the trigger on this value.
     * PHP `che_계략시도.php:37-55`.
     */
    fun getMagicTrialProb(oppose: WarUnit): Double

    /**
     * The magic success probability (NO draw): base `0.7` + the `warMagicSuccessProb` cross-fold.
     * PHP `che_계략시도.php:60-62`.
     */
    fun getMagicSuccessProb(oppose: WarUnit): Double

    /**
     * The `warMagicSuccessDamage` owner-then-oppose cross-fold applied to a chosen magic's raw damage
     * (NO draw; aux = magic name). PHP `che_계략시도.php:74-75`. The FAIL branch stores the RAW table value
     * with NO fold — that asymmetry lives in the trigger.
     */
    fun foldMagicSuccessDamage(oppose: WarUnit, magic: String, raw: Double): Double

    /**
     * The `warMagicFailDamage` owner-then-oppose cross-fold applied to the stored magic damage at the
     * 계략발동/계략실패 step (NO draw; aux = magic name). PHP `che_계략발동.php:30-31` / `che_계략실패.php:30-31`.
     */
    fun foldMagicFailDamage(oppose: WarUnit, magic: String, raw: Double): Double
}
