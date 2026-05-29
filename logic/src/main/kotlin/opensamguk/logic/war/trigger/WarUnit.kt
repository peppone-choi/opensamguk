package opensamguk.logic.war.trigger

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
}
