package opensamguk.logic.world

/**
 * The scenario's `map` + `const` override blocks (the two array_merge inputs of getGameConf that the
 * F6 resolution consumes). PHP `Scenario.php`: `$this->data['map']` and `$this->data['const']` are
 * untyped JSON objects — modeled here as `Map<String, Any?>?` (null = absent block).
 *
 * Per `Scenario.php:269` mapName lives in the `map` block, but a `const` block CAN also set it
 * (array_merge later-wins) — both are honored by [EffectiveGameConst.resolve].
 */
data class ScenarioConf(
    val map: Map<String, Any?>? = null,
    val const: Map<String, Any?>? = null,
    /** The `stat` block (defaultStat* / chiefStatMin); merged FIRST, lowest precedence. */
    val stat: Map<String, Any?> = emptyMap(),
)

/**
 * F6 Task FM1 — `getGameConf` merge pipeline + active-CityConst resolution.
 *
 * Faithful port of `Scenario.php:254-282`:
 * ```
 * $this->gameConf = array_merge($stat, $this->data['map']??[], $this->data['const']??[]);
 * $this->gameConf['mapName'] = $this->gameConf['mapName'] ?? 'che';
 * $this->gameConf['unitSet'] = $this->gameConf['unitSet'] ?? 'che';
 * ```
 * `array_merge` is LATER-wins for string keys: `const` overwrites `map` overwrites `stat`. `mapName`
 * / `unitSet` are forced to 'che' ONLY when absent AFTER the merge. The resolved [gameConf] is FROZEN
 * at scenario load. The active [cityConst] is the [CityConstRegistry] variant for the resolved
 * `mapName` — threaded as a RUNTIME config (NOT a compile-time singleton) into the supply BFS,
 * income ceilings, trade-rate, front-recalc, and the `level==4` invader gate.
 */
class EffectiveGameConst private constructor(
    val gameConf: Map<String, Any?>,
    val cityConst: CityConstVariant,
) {
    val mapName: String get() = gameConf.getValue("mapName") as String
    val unitSet: String get() = gameConf.getValue("unitSet") as String

    companion object {
        const val DEFAULT_MAP = "che"
        const val DEFAULT_UNIT_SET = "che"

        fun resolve(scenario: ScenarioConf): EffectiveGameConst {
            // array_merge(stat, map??[], const??[]) — LATER-wins; insertion order: stat, then map, then const.
            val merged = LinkedHashMap<String, Any?>()
            merged.putAll(scenario.stat)
            scenario.map?.let { merged.putAll(it) }
            scenario.const?.let { merged.putAll(it) }

            // mapName/unitSet ?? 'che' AFTER the merge.
            val mapName = (merged["mapName"] as? String) ?: DEFAULT_MAP
            val unitSet = (merged["unitSet"] as? String) ?: DEFAULT_UNIT_SET
            merged["mapName"] = mapName
            merged["unitSet"] = unitSet

            return EffectiveGameConst(merged, CityConstRegistry.of(mapName))
        }
    }
}
