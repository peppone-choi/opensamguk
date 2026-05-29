package opensamguk.engine.turn

import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral
import opensamguk.logic.domain.Nation as LogicNation

/**
 * Column patch for ONE dirty row — only the columns that actually changed. `meta` deep-changes are
 * carried separately (only the changed/added meta keys, in the post-state's insertion order so the
 * jsonb the flush writes is byte-comparable to the PHP golden's key order).
 *
 * `columns` excludes `meta` (the jsonb column) — that change is described by [meta]. A patch with
 * empty `columns` but non-empty `meta` is still a real change (a meta-only mutation, e.g. the
 * success branch bumping `max_domestic_critical`).
 */
data class RowPatch(
    val id: Int,
    val columns: Map<String, Any?>,
    val meta: Map<String, Any?>,
)

/**
 * F2 — the Immer-`produceWithPatches` replacement and the **single dirty source**.
 *
 * The resolver mutates a [opensamguk.logic.actions.GeneralActionDraft] (immutable `copy()`
 * replacement) and NEVER calls the world's `updateGeneral`/`updateCity`. The handler (F3) diffs the
 * pre-state vs post-state logic [General]/[City] through this recorder; the resulting [RowPatch]es
 * are the ONLY thing that marks a row dirty. Having two dirty sources (the resolver AND the world)
 * would silently diverge the flush (design Risk #4), so this is deliberately the lone path.
 *
 * Faithful to the TS `produceWithPatches(draft, recipe)` shape used in the daemon turn loop: a
 * no-op recipe yields no patch (and nothing dirty); a real mutation yields exactly the changed
 * paths. We model the JSON-Patch "path" coarsely as the column name (the flush maps column → SQL),
 * with `meta` deep-diffed at the key level (the jsonb sub-paths).
 */
class ChangeRecorder {

    private val generalPatches = LinkedHashMap<Int, RowPatch>()
    private val cityPatches = LinkedHashMap<Int, RowPatch>()
    private val nationPatches = LinkedHashMap<Int, RowPatch>()

    /**
     * Per-general rank_data deltas — the 3-Map collapse. At most ONE [RankDelta] per `(general,
     * type)` survives (faithful to `General.php` increaseRankVar/setRankVar: a Set displaces a
     * pending Increment; an Increment over an existing Set folds into the Set; two Increments add).
     */
    private val rankPatches = LinkedHashMap<Int, LinkedHashMap<RankColumn, RankDelta>>()

    val isDirty: Boolean
        get() = generalPatches.isNotEmpty() || cityPatches.isNotEmpty() ||
            nationPatches.isNotEmpty() || rankPatches.isNotEmpty()

    fun dirtyGeneralIds(): Set<Int> = generalPatches.keys.toSet()
    fun dirtyCityIds(): Set<Int> = cityPatches.keys.toSet()
    fun dirtyNationIds(): Set<Int> = nationPatches.keys.toSet()
    fun generalPatches(): List<RowPatch> = generalPatches.values.toList()
    fun cityPatches(): List<RowPatch> = cityPatches.values.toList()
    fun nationPatches(): List<RowPatch> = nationPatches.values.toList()

    /** All recorded rank deltas, per general (the FF2 flush step-8 source). */
    fun rankPatches(): Map<Int, Map<RankColumn, RankDelta>> =
        rankPatches.mapValues { (_, m) -> m.toMap() }

    /** The collapsed rank deltas for one general (empty when none recorded). */
    fun rankDeltas(generalId: Int): Map<RankColumn, RankDelta> =
        rankPatches[generalId]?.toMap() ?: emptyMap()

    /**
     * Diff a general's pre/post draft. Returns the [RowPatch] (and records it as dirty) if anything
     * changed, or `null` if `pre == post` (no-op recipe → not dirty). The `id` is taken from `post`.
     */
    fun diffGeneral(pre: LogicGeneral, post: LogicGeneral): RowPatch? {
        require(pre.id == post.id) { "ChangeRecorder.diffGeneral: id changed (${pre.id} -> ${post.id})" }
        val columns = LinkedHashMap<String, Any?>()
        // slice-relevant scalar columns the che resolver can touch (PHP increaseVar/setVar targets).
        diffCol(columns, "gold", pre.gold, post.gold)
        diffCol(columns, "experience", pre.experience, post.experience)
        diffCol(columns, "dedication", pre.dedication, post.dedication)
        diffCol(columns, "officerLevel", pre.officerLevel, post.officerLevel)
        diffCol(columns, "rice", pre.rice, post.rice)
        diffCol(columns, "injury", pre.injury, post.injury)
        diffCol(columns, "cityId", pre.cityId, post.cityId)
        diffCol(columns, "nationId", pre.nationId, post.nationId)
        diffCol(columns, "leadership", pre.leadership, post.leadership)
        diffCol(columns, "strength", pre.strength, post.strength)
        diffCol(columns, "intel", pre.intel, post.intel)
        // P2 military / equip surface (Task FF1).
        diffCol(columns, "crew", pre.crew, post.crew)
        diffCol(columns, "train", pre.train, post.train)
        diffCol(columns, "atmos", pre.atmos, post.atmos)
        diffCol(columns, "crewTypeId", pre.crewTypeId, post.crewTypeId)
        diffCol(columns, "troop", pre.troop, post.troop)
        diffCol(columns, "horse", pre.horse, post.horse)
        diffCol(columns, "weapon", pre.weapon, post.weapon)
        diffCol(columns, "book", pre.book, post.book)
        diffCol(columns, "item", pre.item, post.item)
        diffCol(columns, "npcType", pre.npcType, post.npcType)
        // last_turn (general-command setResultTurn target) — delete-on-default jsonb.
        diffCol(columns, "lastTurn", pre.lastTurn, post.lastTurn)

        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        generalPatches[post.id] = patch
        return patch
    }

    /**
     * Diff a city's pre/post draft. Returns the [RowPatch] (and records it dirty) if anything
     * changed, or `null` if `pre == post`.
     */
    fun diffCity(pre: LogicCity, post: LogicCity): RowPatch? {
        require(pre.id == post.id) { "ChangeRecorder.diffCity: id changed (${pre.id} -> ${post.id})" }
        val columns = LinkedHashMap<String, Any?>()
        diffCol(columns, "commerce", pre.commerce, post.commerce)
        diffCol(columns, "agriculture", pre.agriculture, post.agriculture)
        diffCol(columns, "commerceMax", pre.commerceMax, post.commerceMax)
        diffCol(columns, "agricultureMax", pre.agricultureMax, post.agricultureMax)
        diffCol(columns, "supplyState", pre.supplyState, post.supplyState)
        diffCol(columns, "frontState", pre.frontState, post.frontState)
        diffCol(columns, "trust", pre.trust, post.trust)
        diffCol(columns, "level", pre.level, post.level)
        diffCol(columns, "nationId", pre.nationId, post.nationId)
        // P2 develop/defense surface (Task FF1): secu/def/wall/pop + each _max, trade, region.
        diffCol(columns, "security", pre.security, post.security)
        diffCol(columns, "securityMax", pre.securityMax, post.securityMax)
        diffCol(columns, "defense", pre.defense, post.defense)
        diffCol(columns, "defenseMax", pre.defenseMax, post.defenseMax)
        diffCol(columns, "wall", pre.wall, post.wall)
        diffCol(columns, "wallMax", pre.wallMax, post.wallMax)
        diffCol(columns, "population", pre.population, post.population)
        diffCol(columns, "populationMax", pre.populationMax, post.populationMax)
        diffCol(columns, "trade", pre.trade, post.trade)
        diffCol(columns, "region", pre.region, post.region)

        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        cityPatches[post.id] = patch
        return patch
    }

    /**
     * Diff a nation's pre/post draft (Task FF1). Returns the [RowPatch] (and records it dirty) if
     * gold/rice/capital/name/color/tech/level changed, or `meta` deep-changed (gennum/capset/rate/
     * bill/aux …). Returns `null` if `pre == post`. The scalar columns map to the V1 `nation` row
     * column names (`capital_city_id`, `type_code`); `gennum`/`capset` ride the `meta` jsonb and are
     * carried in [RowPatch.meta] (a 증축/감축/천도 bumps `capset`, invalidating term-stacks).
     */
    fun diffNation(pre: LogicNation, post: LogicNation): RowPatch? {
        require(pre.id == post.id) { "ChangeRecorder.diffNation: id changed (${pre.id} -> ${post.id})" }
        val columns = LinkedHashMap<String, Any?>()
        diffCol(columns, "name", pre.name, post.name)
        diffCol(columns, "color", pre.color, post.color)
        diffCol(columns, "capital_city_id", pre.capitalCityId, post.capitalCityId)
        diffCol(columns, "gold", pre.gold, post.gold)
        diffCol(columns, "rice", pre.rice, post.rice)
        diffCol(columns, "tech", pre.tech, post.tech)
        diffCol(columns, "level", pre.level, post.level)
        diffCol(columns, "type_code", pre.typeCode, post.typeCode)

        // capset/gennum ride meta; diffMeta walks the post-state in insertion order so the jsonb
        // the flush writes preserves PHP `Json::encode` key order.
        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        nationPatches[post.id] = patch
        return patch
    }

    /**
     * Record a rank_data `value = value + [value]` (`increaseRankVar`, `General.php:641-660`):
     *  - an existing `Set` folds the increase into the Set,
     *  - an existing `Increment` accumulates,
     *  - otherwise a fresh `Increment`.
     */
    fun recordRankIncrease(generalId: Int, column: RankColumn, value: Int) {
        val map = rankPatches.getOrPut(generalId) { LinkedHashMap() }
        map[column] = when (val existing = map[column]) {
            is RankDelta.Set -> RankDelta.Set(existing.value + value)
            is RankDelta.Increment -> RankDelta.Increment(existing.value + value)
            null -> RankDelta.Increment(value)
        }
    }

    /**
     * Record a rank_data `value = [value]` (`setRankVar`, `General.php:662-670`): a Set displaces
     * any pending Increment/Set for that `(general, type)` — at most one delta survives.
     */
    fun recordRankSet(generalId: Int, column: RankColumn, value: Int) {
        val map = rankPatches.getOrPut(generalId) { LinkedHashMap() }
        map[column] = RankDelta.Set(value)
    }

    private fun diffCol(out: LinkedHashMap<String, Any?>, name: String, pre: Any?, post: Any?) {
        if (pre != post) out[name] = post
    }

    /**
     * Deep-diff `meta` at the key level. Returns ONLY the changed/added keys, walking the
     * post-state in its insertion order (so the patch — and the jsonb it flushes — preserves PHP
     * `Json::encode` key order). Removed keys are not expected in the P1 slice (the che resolver
     * only sets/bumps keys), so they are not modeled here.
     */
    private fun diffMeta(pre: Map<String, Any?>, post: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((k, v) in post) {
            if (!pre.containsKey(k) || pre[k] != v) out[k] = v
        }
        return out
    }
}
