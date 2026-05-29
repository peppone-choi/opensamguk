package opensamguk.logic.items

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionModuleSource

/**
 * The four equip-slot codes in their PHP `getActionList()` fold order (`General.php:120-123`,
 * `:798` itemObjs = [horse, weapon, book, item]). Used by [ItemRegistry.equippedModules] to enumerate
 * a general's equipped items in the canonical order before the falsy-skip + dedup.
 */
val ITEM_SLOT_ORDER: List<(General) -> String> = listOf(
    { g -> g.horse }, { g -> g.weapon }, { g -> g.book }, { g -> g.item },
)

/**
 * Declarative +stat item — a faithful port of PHP `BaseStatItem` (`BaseStatItem.php:6-39`).
 *
 * The stat is parsed from the class name at construction (`explode('_', static::class)`):
 *   - token[-3] = category (명마/무기/서적) → ITEM_TYPE → [statNick, statType]
 *   - token[-2] = statValue (numeric)
 *   - token[-1] = rawName
 * `onCalcStat(statName, value)` returns `value + statValue` iff `statName === statType`, else identity.
 * All other hooks are DefaultAction identity. ZERO RNG.
 *
 * `name`/`info` reproduce the PHP `sprintf` strings verbatim:
 *   name = "{rawName}(+{statValue})" ; info = "{statNick} +{statValue}".
 */
open class BaseStatItemModule(
    /** The item code = PHP short class name incl `che_` prefix (e.g. `che_명마_01_노기`). */
    val code: String,
    val statNick: String,
    val statType: String,
    val statValue: Int,
    val rawName: String,
) : GeneralActionModule {

    /** PHP `sprintf('%s(+%d)', rawName, statValue)`. */
    val name: String = "$rawName(+$statValue)"

    /** PHP `sprintf('%s +%d', statNick, statValue)`. */
    open val info: String = "$statNick +$statValue"

    /** BaseStatItem.php:34-39 — +statValue only when the requested stat matches this item's statType. */
    override fun onCalcStat(general: General, statName: String, value: Double, aux: Map<String, Any?>): Double =
        if (statName == statType) value + statValue else value
}

/**
 * The ITEM_TYPE map (`BaseStatItem.php:16-20`): the category token → (statNick, statType).
 * 명마→통솔/leadership, 무기→무력/strength, 서적→지력/intel.
 */
private val ITEM_TYPE: Map<String, Pair<String, String>> = linkedMapOf(
    "명마" to ("통솔" to "leadership"),
    "무기" to ("무력" to "strength"),
    "서적" to ("지력" to "intel"),
)

/**
 * The ITEMS-family [GeneralActionModuleSource] (the `ItemRegistry` the
 * [opensamguk.logic.stats.GeneralActionModuleFactory] looks up — the S-MODULES factory body is NOT edited;
 * it resolves the four equip-slot codes through this registry).
 *
 * `resolve(code)` mirrors PHP `buildItemClass($type)` (`func_converter.php:244-257`): null/blank/`None`
 * → null (skipped slot); otherwise builds the concrete module and CACHES it (the PHP static `$cache`),
 * so two `resolve` calls of the same code return the SAME instance.
 *
 * The declarative +stat branch parses the code; non-declarative extra-hook items (IT2) register their
 * builders into [extraHookBuilders] so this single registry resolves the entire P2-domestic item surface
 * in one place (the factory only ever sees one `ItemRegistry`).
 *
 * @param relYearProvider supplies the relative game year (`year - startYear`) to year-scaled ability
 *        items (IT2); a plain int reader keeps the [GeneralActionModuleSource.resolve] signature intact
 *        (PHP reads `game_env` at hook time — reading it at module-build time is behaviourally equivalent
 *        for the per-turn resolve).
 */
class ItemRegistry(
    val relYearProvider: () -> Int = { 0 },
) : GeneralActionModuleSource {

    private val cache = HashMap<String, GeneralActionModule>()

    override fun resolve(code: String?): GeneralActionModule? {
        if (code.isNullOrBlank() || code == "None") return null
        cache[code]?.let { return it }
        val built = build(code) ?: return null
        cache[code] = built
        return built
    }

    private fun build(code: String): GeneralActionModule? {
        extraHookBuilders[code]?.let { return it(this) }
        return buildStatItem(code)
    }

    /** Parse the declarative class-name tokens; null when the code is not a 명마/무기/서적 +stat item. */
    private fun buildStatItem(code: String): BaseStatItemModule? {
        val tokens = code.split('_')
        if (tokens.size < 3) return null
        val category = tokens[tokens.size - 3]
        val (statNick, statType) = ITEM_TYPE[category] ?: return null
        val statValue = tokens[tokens.size - 2].toIntOrNull() ?: return null
        val rawName = tokens[tokens.size - 1]
        return BaseStatItemModule(code, statNick, statType, statValue, rawName)
    }

    companion object {
        /**
         * Extra-hook (non-pure-declarative) item builders keyed by item code (IT2 populates this).
         * Kept on the companion so both IT1 and IT2 register into ONE table; each builder receives the
         * owning [ItemRegistry] (for the relYear provider).
         */
        val extraHookBuilders: MutableMap<String, (ItemRegistry) -> GeneralActionModule> = linkedMapOf()

        /**
         * Enumerate a general's equipped item modules in the canonical horse→weapon→book→item fold order,
         * skipping falsy (None/blank) slots AND DEDUPING by code — a faithful port of
         * `listEquippedItemKeys` (research Unit 11 / items/utils.ts:28-47): the SAME item code in two slots
         * folds ONCE. PHP `getActionList` itself does not dedup; this enumerator is the dedup-aware view
         * used wherever the equipped set (not the raw 4 slots) is folded.
         */
        fun equippedModules(general: General, registry: ItemRegistry): List<GeneralActionModule> {
            val seen = HashSet<String>()
            val out = ArrayList<GeneralActionModule>(4)
            for (slot in ITEM_SLOT_ORDER) {
                val code = slot(general)
                if (code.isBlank() || code == "None") continue
                if (!seen.add(code)) continue
                registry.resolve(code)?.let { out.add(it) }
            }
            return out
        }
    }
}
