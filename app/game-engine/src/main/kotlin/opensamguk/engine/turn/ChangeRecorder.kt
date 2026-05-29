package opensamguk.engine.turn

import opensamguk.logic.domain.City as LogicCity
import opensamguk.logic.domain.General as LogicGeneral

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

    val isDirty: Boolean get() = generalPatches.isNotEmpty() || cityPatches.isNotEmpty()

    fun dirtyGeneralIds(): Set<Int> = generalPatches.keys.toSet()
    fun dirtyCityIds(): Set<Int> = cityPatches.keys.toSet()
    fun generalPatches(): List<RowPatch> = generalPatches.values.toList()
    fun cityPatches(): List<RowPatch> = cityPatches.values.toList()

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

        val metaPatch = diffMeta(pre.meta, post.meta)

        if (columns.isEmpty() && metaPatch.isEmpty()) return null
        val patch = RowPatch(post.id, columns, metaPatch)
        cityPatches[post.id] = patch
        return patch
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
