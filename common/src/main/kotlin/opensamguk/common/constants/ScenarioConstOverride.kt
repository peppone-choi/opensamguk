package opensamguk.common.constants

/**
 * Typed wrapper over the untyped scenario override map.
 *
 * `scenario.config.const` is `Record<string,unknown>` (parseScenario.ts:178, worldLoader
 * zScenarioConfig.const = z.record) — modeled here as Map<String, Any?>.
 */
@JvmInline
value class ScenarioConstOverride(val values: Map<String, Any?>) {
    fun int(key: String): Int? = (values[key] as? Number)?.toInt()
    fun double(key: String): Double? = (values[key] as? Number)?.toDouble()
    fun str(key: String): String? = values[key] as? String
    operator fun contains(key: String) = key in values
}
