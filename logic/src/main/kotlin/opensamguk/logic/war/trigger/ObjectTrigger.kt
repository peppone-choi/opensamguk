package opensamguk.logic.war.trigger

import opensamguk.common.rng.RandUtil

/**
 * The mutable per-fire environment threaded through a [TriggerCaller.fire] chain.
 *
 * PHP threads a plain `array` (`TriggerCaller::fire` `$env`); each trigger's `action` returns the
 * (possibly mutated) env which becomes the next trigger's input. We model it as a `LinkedHashMap` so
 * insertion order is preserved (the PHP arrays the env stitches — `e_attacker`/`e_defender`/`magic`/
 * `stopNextAction` — are insertion-ordered) and the war-side env-split ([BaseWarUnitTrigger]) re-stitches
 * sub-maps by reference.
 */
typealias TriggerEnv = LinkedHashMap<String, Any?>

/**
 * Port target = PHP `sammo/ObjectTrigger.php:5-36`.
 *
 * The priority-bucketed battle trigger contract. The [TriggerCaller.fire] iteration over these triggers
 * (priority ASCENDING, then insertion order) IS the battle RNG draw order — so the `priority` band consts
 * and the dedup-keyed [getUniqueID] are the load-bearing parity surface, NOT mere bookkeeping.
 *
 * "lower number = fires FIRST" (`ObjectTrigger.php:5` comment `낮을 수록 우선순위가 높다`).
 */
abstract class ObjectTrigger : Trigger {
    companion object {
        const val PRIORITY_MIN: Int = 0
        const val PRIORITY_BEGIN: Int = 10000
        const val PRIORITY_PRE: Int = 20000
        const val PRIORITY_BODY: Int = 30000
        const val PRIORITY_POST: Int = 40000
        const val PRIORITY_FINAL: Int = 50000
    }

    /** Lower number fires FIRST. PHP `$priority`. */
    abstract override val priority: Int

    /**
     * Stable object identity token. PHP uses `spl_object_id($this->object)` (non-reproducible across runs);
     * we substitute a STABLE per-unit id string (decision #4 — `WarUnit.unitId`) so the dedup key is
     * deterministic. Empty string mirrors PHP's `$this->object === null ⇒ $objID = ''`.
     */
    abstract val objectId: String

    /**
     * Dedup key = `"{priority}_{FQN}_{objID}"` (`ObjectTrigger.php:27-35`). The fully-qualified class name
     * is the stable substitute for PHP `static::class`. [BaseWarUnitTrigger] OVERRIDES this to append
     * `_{raiseType}` (raiseType coexistence, decision #4).
     */
    override fun getUniqueID(): String {
        val fqn = this::class.qualifiedName
        return "${priority}_${fqn}_${objectId}"
    }
}

/**
 * The registry-facing trigger contract (the type [TriggerCaller] stores + fires). Separated from the
 * abstract [ObjectTrigger] base so the caller's draw-order machinery depends only on the interface
 * (mirrors the core2026 `Trigger<TContext,TEnv,TArg>` interface that the generic `TriggerCaller` holds).
 */
interface Trigger {
    /** Lower number fires FIRST. */
    val priority: Int

    /** Dedup key within a [TriggerCaller] priority bucket. */
    fun getUniqueID(): String

    /**
     * Run the trigger against the shared [rng], the threaded [env], and the optional [arg]
     * (the `[attacker, defender]` pair in the war path). Returns the (possibly mutated) env.
     * PHP `ObjectTrigger::action(RandUtil $rng, ?array $env, $arg): ?array`.
     */
    fun action(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv
}
