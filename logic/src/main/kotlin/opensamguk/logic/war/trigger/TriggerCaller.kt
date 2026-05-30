package opensamguk.logic.war.trigger

import opensamguk.common.rng.RandUtil

/**
 * Port target = PHP `sammo/TriggerCaller.php` — constructor (:13-51), [merge] (:80-119), [fire] (:121-129).
 *
 * **`append()` is INTENTIONALLY NOT ported (decision #2):** the canonical battle path is the constructor
 * + [merge] (`General.php:920-934`); PHP's `append()` has a latent line-75 bucket-clobber bug, is
 * unreachable in battle, and core2026 diverged it additive. We port constructor + merge + fire ONLY.
 *
 * The [fire] iteration order IS the battle RNG draw order:
 *   - outer map sorted ASCENDING by int priority (PHP `ksort`);
 *   - inner bucket INSERTION-ordered (decision #3 — NEVER key-sorted): two triggers at the same priority
 *     (계략발동 & 계략실패 both POST+300=40300) fire in constructor ARG order;
 *   - the inner key is the trigger's [Trigger.getUniqueID] → that key IS the dedup (later overwrites earlier).
 */
abstract class TriggerCaller(vararg triggerList: Trigger) {

    /**
     * `LinkedHashMap<Int, LinkedHashMap<String, Trigger>>` — outer kept ascending-sorted (the PHP `ksort`
     * end-state), inner insertion-ordered keyed by uniqueID. PHP `$triggerListByPriority`.
     */
    protected val triggerListByPriority: LinkedHashMap<Int, LinkedHashMap<String, Trigger>> = LinkedHashMap()

    /** PHP `abstract function checkValidTrigger(ObjectTrigger): bool`. FT3's WarUnitTriggerCaller = instanceof gate. */
    abstract fun checkValidTrigger(trigger: Trigger): Boolean

    init {
        // PHP `__construct(ObjectTrigger ...$triggerList)` (:13-51): validate + arg-order insert; track a
        // `sorted` flag and only re-sort (ksort) if an out-of-order priority was seen. We always normalize to
        // ascending afterwards (the PHP end-state) so [fire]'s foreach is ascending regardless of arg order.
        if (triggerList.isNotEmpty()) {
            for (trigger in triggerList) {
                require(checkValidTrigger(trigger)) { "Invalid Trigger Type" }
                insert(trigger)
            }
            sortOuterAscending()
        }
    }

    fun isEmpty(): Boolean = triggerListByPriority.isEmpty()

    /**
     * Insert a single trigger into its priority bucket keyed by uniqueID (the dedup key — later overwrites
     * earlier within a bucket, preserving the FIRST insertion position, matching PHP `[$uniqueID=>$trigger]`).
     * Internal — NOT the public `append()` (decision #2); used only by the constructor + [merge].
     */
    private fun insert(trigger: Trigger) {
        val bucket = triggerListByPriority.getOrPut(trigger.priority) { LinkedHashMap() }
        bucket[trigger.getUniqueID()] = trigger
    }

    /** Re-key the outer map into ascending priority order (PHP `ksort($this->triggerListByPriority)`). */
    private fun sortOuterAscending() {
        if (triggerListByPriority.size <= 1) return
        val sorted = triggerListByPriority.entries.sortedBy { it.key }
        val snapshot = LinkedHashMap<Int, LinkedHashMap<String, Trigger>>()
        for (e in sorted) snapshot[e.key] = e.value
        triggerListByPriority.clear()
        triggerListByPriority.putAll(snapshot)
    }

    /**
     * Port of PHP `merge(?TriggerCaller $other)` (:80-119). Both maps are already ascending-sorted, so this
     * is a sorted merge-walk: lower-priority bucket wins position; on a SHARED priority, `array_merge(lhs, rhs)`
     * → LHS uniqueIDs first (in order) then RHS appended (RHS overwrites a colliding key VALUE but keeps the
     * LHS position — PHP array_merge semantics for string keys; LinkedHashMap.put on an existing key does the
     * same). Mutates THIS and returns it. `null` other = no-op (the per-source caller can be null).
     */
    fun merge(other: TriggerCaller?): TriggerCaller {
        if (other == null) return this

        val merged = LinkedHashMap<Int, LinkedHashMap<String, Trigger>>()
        val lhsKeys = triggerListByPriority.keys.toList()
        val rhsKeys = other.triggerListByPriority.keys.toList()
        var li = 0
        var ri = 0
        while (li < lhsKeys.size && ri < rhsKeys.size) {
            val lk = lhsKeys[li]
            val rk = rhsKeys[ri]
            when {
                lk < rk -> { merged[lk] = triggerListByPriority.getValue(lk); li++ }
                rk < lk -> { merged[rk] = other.triggerListByPriority.getValue(rk); ri++ }
                else -> {
                    // shared bucket: array_merge(lhs, rhs) — LHS first (in order), then RHS appended/overwrite.
                    val bucket = LinkedHashMap<String, Trigger>()
                    bucket.putAll(triggerListByPriority.getValue(lk))
                    bucket.putAll(other.triggerListByPriority.getValue(rk))
                    merged[lk] = bucket
                    li++; ri++
                }
            }
        }
        while (li < lhsKeys.size) { val lk = lhsKeys[li]; merged[lk] = triggerListByPriority.getValue(lk); li++ }
        while (ri < rhsKeys.size) { val rk = rhsKeys[ri]; merged[rk] = other.triggerListByPriority.getValue(rk); ri++ }

        triggerListByPriority.clear()
        triggerListByPriority.putAll(merged)
        return this
    }

    /**
     * Port of PHP `fire(RandUtil $rng, array $env, $arg)` (:121-129). Nested foreach
     * priority→bucket = THE DRAW ORDER. Threads `env` (each trigger returns the next trigger's env).
     * `stopNextAction` is honored BY the triggers themselves ([BaseWarUnitTrigger] no-ops once set), so we
     * iterate every trigger unconditionally — the short-circuit is draw-order-affecting and must stay exact.
     */
    fun fire(rng: RandUtil, env: TriggerEnv, arg: Any?): TriggerEnv {
        var currentEnv = env
        for ((_, bucket) in triggerListByPriority) {
            for (trigger in bucket.values) {
                currentEnv = trigger.action(rng, currentEnv, arg)
            }
        }
        return currentEnv
    }
}
