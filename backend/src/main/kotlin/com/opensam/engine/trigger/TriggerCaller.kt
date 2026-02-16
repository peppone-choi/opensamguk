package com.opensam.engine.trigger

/**
 * Priority-based trigger system (legacy parity).
 *
 * Triggers are small, prioritized actions that modify command/battle behavior.
 * They fire in priority order (lower = earlier).
 *
 * Priority constants from legacy:
 *   PRIORITY_BEGIN = 10000  (first to run)
 *   PRIORITY_PRE   = 20000
 *   PRIORITY_BODY  = 30000
 *   PRIORITY_POST  = 40000
 *   PRIORITY_FINAL = 50000  (last to run)
 */
object TriggerPriority {
    const val BEGIN = 10000
    const val PRE = 20000
    const val BODY = 30000
    const val POST = 40000
    const val FINAL = 50000
}

/**
 * A single trigger action with a unique ID and priority.
 */
interface ObjectTrigger {
    val uniqueId: String
    val priority: Int

    /**
     * Execute this trigger. Returns false to stop further triggers in the chain.
     */
    fun action(env: TriggerEnv): Boolean
}

/**
 * Mutable environment passed through the trigger chain.
 */
data class TriggerEnv(
    val worldId: Long,
    val year: Int,
    val month: Int,
    val generalId: Long,
    val vars: MutableMap<String, Any> = mutableMapOf(),
    var stopNextAction: Boolean = false,
)

/**
 * Groups triggers by priority, deduplicates by uniqueId, and fires in order.
 */
class TriggerCaller {
    private val triggers = mutableMapOf<String, ObjectTrigger>()

    fun addTrigger(trigger: ObjectTrigger) {
        // Later additions with same uniqueId override earlier ones (legacy dedup semantics)
        triggers[trigger.uniqueId] = trigger
    }

    fun addAll(triggerList: List<out ObjectTrigger>) {
        for (trigger in triggerList) {
            addTrigger(trigger)
        }
    }

    /**
     * Fire all triggers in priority order. Stops if any trigger sets stopNextAction.
     */
    fun fire(env: TriggerEnv) {
        val sorted = triggers.values.sortedBy { it.priority }
        for (trigger in sorted) {
            if (env.stopNextAction) break
            val continueChain = trigger.action(env)
            if (!continueChain) {
                env.stopNextAction = true
            }
        }
    }

    fun isEmpty(): Boolean = triggers.isEmpty()

    fun size(): Int = triggers.size
}
