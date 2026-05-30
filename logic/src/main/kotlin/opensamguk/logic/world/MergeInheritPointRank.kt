package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

/**
 * B5 / Task LT3 — MergeInheritPointRank (PHP `Event/Action/MergeInheritPointRank.php`).
 *
 * NOT a light Action. It folds the InheritancePointManager (deferred to P6) into the `rank_data`
 * `inherit_earned_dyn` rows, then runs two correlated derived-value updates. The plan resolves the
 * P3/P6 contradiction with option (b) — the **P3 bound**: across the scenario_1010 N-month replay no
 * command earns an inherit point, so `getInheritancePointFromAll` is ALL-ZERO and the fold is a NO-OP;
 * only the `rank_data` TYPE rows (the reconstructed merge rows + the two correlated updates) must
 * byte-match (pinned in G1a). The P6 manager is modeled here as an injectable point source whose P3
 * default is [ZERO_POINT_SOURCE]; the recorded-delta path stays open for when P6 lands the real manager.
 *
 * It fires at month==1 (index 0 of the January row), month==7 (index 0 of the July row), AND the
 * `united` target (P3 stubs `united` as a no-op target, but the row+id ordering still byte-matches via
 * the F2-owned seed). F2 names it; B5 supplies + registers the leaf.
 *
 * Faithful port of the run body (MergeInheritPointRank.php:17-65):
 *   1. `General::createObjListFromDB(null,null)` → ALL generals in PK order.
 *   2. `points[id] = 0` for each; fold over `InheritanceKey::cases()` EXCEPT `previous`, adding
 *      `getInheritancePointFromAll(generals, key)[id] ?? 0`.
 *   3. build `pointsPairs = [{nation_id, general_id, type: inherit_earned_dyn, value: point}]`.
 *   4. `DELETE rank_data WHERE type = inherit_earned_dyn`; `INSERT pointsPairs`.
 *   5. `UPDATE rank_data SET value = SUM(value WHERE type IN (inherit_earned_act, inherit_earned_dyn))
 *      per general WHERE type = inherit_earned`.
 *   6. `UPDATE rank_data SET value = (value WHERE type = inherit_spent_dyn) per general
 *      WHERE type = inherit_spent`.
 */
object MergeInheritPointRank {

    // RankColumn string values (Enums/RankColumn.php) — the `value` (NOT the case name) is the DB type.
    /** `inherit_point_earned` → `inherit_earned`. */
    const val TYPE_EARNED = "inherit_earned"

    /** `inherit_point_spent` → `inherit_spent`. */
    const val TYPE_SPENT = "inherit_spent"

    /** `inherit_point_earned_by_merge` → `inherit_earned_dyn` (the merge rows this Action writes). */
    const val TYPE_EARNED_DYN = "inherit_earned_dyn"

    /** `inherit_point_earned_by_action` → `inherit_earned_act`. */
    const val TYPE_EARNED_ACT = "inherit_earned_act"

    /** `inherit_point_spent_dynamic` → `inherit_spent_dyn`. */
    const val TYPE_SPENT_DYN = "inherit_spent_dyn"

    /**
     * The `InheritanceKey::cases()` folded — ALL cases EXCEPT `previous` (MergeInheritPointRank.php:30-32
     * skips `InheritanceKey::previous`). Order = the PHP enum declaration order (Enums/InheritanceKey.php).
     */
    private val FOLDED_KEYS = listOf(
        "lived_month",
        "max_belong",
        "max_domestic_critical",
        "active_action",
        // snipe_combat is commented out in the PHP enum.
        "combat",
        "sabotage",
        "unifier",
        "dex",
        "tournament",
        "betting",
    )

    /** The InheritanceKey value-strings folded (all except `previous`). */
    fun foldedKeys(): List<String> = FOLDED_KEYS

    /**
     * The P3 inherit-point source: ALL-ZERO (no command earns a point through the N-month replay; the
     * bound is pinned in G1a). Returns an empty map for every key → every general folds to 0.
     */
    val ZERO_POINT_SOURCE: (String) -> Map<Int, Int> = { _ -> emptyMap() }
}

/** One general the merge folds over (PHP `General` reduced to the merge inputs). */
data class MergeGeneral(val generalId: Int, val nationId: Int)

/** One reconstructed `rank_data` `inherit_earned_dyn` merge row. */
data class MergeRow(val nationId: Int, val generalId: Int, val type: String, val value: Int)

/**
 * A correlated derived-value update intent (PHP's two `UPDATE rank_data D SET value = (subquery)`):
 * set each general's [targetType] row to the per-general aggregate of its [sourceTypes] rows.
 */
data class DerivedUpdate(val targetType: String, val sourceTypes: List<String>)

/** The structured result the [MergeInheritPointRankAction] applies to the world + flush. */
data class MergeInheritResult(
    /** `DELETE rank_data WHERE type = deleteType` runs before the reinsert. */
    val deleteType: String,
    /** The reinserted `inherit_earned_dyn` rows, in general (PK) iteration order. */
    val mergeRows: List<MergeRow>,
    /** `inherit_earned = SUM(inherit_earned_act + inherit_earned_dyn)` per general. */
    val earnedUpdate: DerivedUpdate,
    /** `inherit_spent = inherit_spent_dyn` per general. */
    val spentUpdate: DerivedUpdate,
)

/**
 * Pure faithful port of `MergeInheritPointRank::run`. [generals] are iterated in the order supplied
 * (the caller's contract = `General::createObjListFromDB` PK order). [pointSource] returns the per-key
 * `getInheritancePointFromAll` map (general id → point); the P3 default is [MergeInheritPointRank.ZERO_POINT_SOURCE].
 */
fun mergeInheritPointRank(
    generals: List<MergeGeneral>,
    pointSource: (String) -> Map<Int, Int>,
): MergeInheritResult {
    // points[id] = 0 (MergeInheritPointRank.php:23-28), then fold over the non-previous keys.
    val points = LinkedHashMap<Int, Int>()
    for (g in generals) points[g.generalId] = 0
    for (key in MergeInheritPointRank.foldedKeys()) {
        val sub = pointSource(key)
        for (g in generals) {
            points[g.generalId] = points.getValue(g.generalId) + (sub[g.generalId] ?: 0) // ?? 0
        }
    }
    // pointsPairs (MergeInheritPointRank.php:41-49) — one merge row per general, in iteration order.
    val mergeRows = generals.map { g ->
        MergeRow(
            nationId = g.nationId,
            generalId = g.generalId,
            type = MergeInheritPointRank.TYPE_EARNED_DYN,
            value = points.getValue(g.generalId),
        )
    }
    return MergeInheritResult(
        deleteType = MergeInheritPointRank.TYPE_EARNED_DYN,
        mergeRows = mergeRows,
        earnedUpdate = DerivedUpdate(
            targetType = MergeInheritPointRank.TYPE_EARNED,
            sourceTypes = listOf(MergeInheritPointRank.TYPE_EARNED_ACT, MergeInheritPointRank.TYPE_EARNED_DYN),
        ),
        spentUpdate = DerivedUpdate(
            targetType = MergeInheritPointRank.TYPE_SPENT,
            sourceTypes = listOf(MergeInheritPointRank.TYPE_SPENT_DYN),
        ),
    )
}

/**
 * The world seam MergeInheritPointRank needs (supplied by the tick's richer context). B5-owned + minimal
 * so the leaf stays in its file boundary while the daemon-side world adapter (G1 wiring) implements it.
 */
interface MergeInheritWorld {
    /** ALL generals in PK order (PHP `General::createObjListFromDB(null,null)`). */
    fun mergeGenerals(): List<MergeGeneral>

    /** The P6 InheritancePointManager seam: per-key `getInheritancePointFromAll` (P3 default = all-zero). */
    fun inheritancePoints(key: String): Map<Int, Int>

    /** Apply the DELETE+INSERT of the merge rows + the two correlated derived updates (the rank_data fold). */
    fun applyMerge(result: MergeInheritResult)

    companion object {
        /** The env key the tick threads the world view under. */
        const val ENV_KEY = "mergeInheritWorld"
    }
}

/**
 * The F2 event leaf for "MergeInheritPointRank". Reads [MergeInheritWorld] from `env[MergeInheritWorld.ENV_KEY]`
 * (absent → no-op, since the daemon/G1 world-context seam lands later); folds via the all-zero P3 point
 * source the world exposes; applies the rank_data fold. Registered into the F2-owned [EventActionFactory]
 * by name (the lone per-family touch). F2 names it at month==1 / month==7 index 0 + the `united` row.
 */
class MergeInheritPointRankAction(@Suppress("UNUSED_PARAMETER") args: List<JsonElement> = emptyList()) : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx.env[MergeInheritWorld.ENV_KEY] as? MergeInheritWorld ?: return
        val result = mergeInheritPointRank(world.mergeGenerals()) { key -> world.inheritancePoints(key) }
        world.applyMerge(result)
    }

    companion object {
        const val NAME = "MergeInheritPointRank"

        /** Register the leaf into the F2-owned factory by name (plan §append protocol). */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args -> MergeInheritPointRankAction(args) }
    }
}
