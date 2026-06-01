package opensamguk.logic.inheritance

import opensamguk.logic.domain.General

/**
 * Factory that creates the inherit buff module pair (general + war) from a resolved [InheritBuff].
 *
 * Faithful port of TypeScript `createInheritBuffModules` (legacy_core2026 inheritBuff.ts).
 * The buff is resolved from context.general.triggerState.meta.inheritBuff (priority)
 * with fallback to context.general.meta.inheritBuff.
 *
 * Usage:
 * ```kotlin
 * val buffMap = general.triggerState.meta["inheritBuff"] as? Map<String, Any?>
 *     ?: general.meta["inheritBuff"] as? Map<String, Any?>
 * val buff = InheritBuff.fromMap(buffMap)
 * val (generalModule, warModule) = InheritBuffModuleFactory.createModules(buff)
 * ```
 */
object InheritBuffModuleFactory {

    /**
     * Create the general + war module pair from a resolved [InheritBuff].
     * @return Pair of (General module, War module)
     */
    fun createModules(buff: InheritBuff): Pair<InheritBuffGeneralModule, InheritBuffWarModule> {
        return InheritBuffGeneralModule(buff) to InheritBuffWarModule(buff)
    }

    /**
     * Convenience: resolve the buff from a general's meta (with triggerState priority),
     * then create the modules. Returns null if no buff is found.
     */
    fun resolveAndCreate(general: General): Pair<InheritBuffGeneralModule, InheritBuffWarModule>? {
        // 1. Try triggerState.meta.inheritBuff (highest priority)
        @Suppress("UNCHECKED_CAST")
        val triggerBuff = general.meta["triggerState"]?.let { ts ->
            (ts as? Map<String, Any?>)?.get("meta")?.let { m ->
                (m as? Map<String, Any?>)?.get("inheritBuff") as? Map<String, Any?>
            }
        }

        val buffMap = triggerBuff
            ?: run {
                // 2. Fallback: general.meta.inheritBuff
                @Suppress("UNCHECKED_CAST")
                general.meta["inheritBuff"] as? Map<String, Any?>
            }

        if (buffMap == null || buffMap.isEmpty()) return null

        val buff = InheritBuff.fromMap(buffMap)
        return createModules(buff)
    }
}
