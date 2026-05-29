package opensamguk.logic.world

import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * B5 / Task LT3 — MergeInheritPointRank (PHP `Event/Action/MergeInheritPointRank.php`).
 *
 * NOT a light Action: it folds the deferred-to-P6 InheritancePointManager. The plan resolves the
 * contradiction via option (b) — the **P3 bound**: through the scenario_1010 N-month replay no command
 * has earned an inherit point, so `getInheritancePointFromAll` is ALL-ZERO and the fold is a NO-OP; only
 * the `rank_data` TYPE rows (the `inherit_earned_dyn` merge rows reconstructed + the two correlated
 * derived-value updates) must byte-match. The P6 manager is modeled as an injectable point source
 * (default = all-zero); the recorded-delta path stays open for P6.
 *
 * Faithful port of the run body (MergeInheritPointRank.php:17-65):
 *   1. ALL generals (PK order = General::createObjListFromDB).
 *   2. points[id] = sum over InheritanceKey::cases() EXCEPT `previous` of pointSource(key)[id] ?? 0.
 *   3. rows = [{nation_id, general_id, type: inherit_earned_dyn, value: point}] per general (PK order).
 *   4. DELETE rank_data WHERE type = inherit_earned_dyn; INSERT rows.
 *   5. UPDATE inherit_earned = SUM(inherit_earned_act + inherit_earned_dyn) per general.
 *   6. UPDATE inherit_spent  = inherit_spent_dyn per general.
 */
class MergeInheritPointRankTest {

    private fun gen(id: Int, nationId: Int) = MergeGeneral(generalId = id, nationId = nationId)

    // ── the P3 bound: all-zero point source → no-op fold ─────────────────────
    @Test
    fun `all-zero point source folds to zero merge rows (the P3 bound)`() {
        val generals = listOf(gen(3, 1), gen(1, 2), gen(2, 0)) // caller supplies PK order; here as given
        val result = mergeInheritPointRank(generals, pointSource = MergeInheritPointRank.ZERO_POINT_SOURCE)
        // one merge row per general, value 0, in the SAME order the generals were iterated.
        assertEquals(3, result.mergeRows.size)
        assertEquals(listOf(3, 1, 2), result.mergeRows.map { it.generalId }, "rows in iterated (PK) order")
        assertTrue(result.mergeRows.all { it.value == 0 }, "all-zero fold")
        assertEquals(listOf(1, 2, 0), result.mergeRows.map { it.nationId }, "nation_id carried per general")
        assertEquals(MergeInheritPointRank.TYPE_EARNED_DYN, result.mergeRows[0].type)
    }

    @Test
    fun `the fold sums every InheritanceKey EXCEPT previous`() {
        // A source that gives 10 for EVERY key (incl. previous). previous must be EXCLUDED.
        val source: (String) -> Map<Int, Int> = { _ -> mapOf(1 to 10) }
        val result = mergeInheritPointRank(listOf(gen(1, 1)), pointSource = source)
        val nonPreviousKeyCount = MergeInheritPointRank.foldedKeys().size
        assertEquals(10 * nonPreviousKeyCount, result.mergeRows[0].value,
            "fold over all keys except previous; each contributes 10")
        assertTrue("previous" !in MergeInheritPointRank.foldedKeys(), "previous is excluded from the fold")
    }

    @Test
    fun `missing general entry in a key map contributes 0 (?? 0)`() {
        val source: (String) -> Map<Int, Int> = { key -> if (key == "combat") mapOf(1 to 5) else emptyMap() }
        val result = mergeInheritPointRank(listOf(gen(1, 1), gen(2, 1)), pointSource = source)
        assertEquals(5, result.mergeRows.first { it.generalId == 1 }.value)
        assertEquals(0, result.mergeRows.first { it.generalId == 2 }.value, "absent → 0")
    }

    // ── the two correlated derived-value updates ─────────────────────────────
    @Test
    fun `derived updates target inherit_earned and inherit_spent with the correct source types`() {
        val result = mergeInheritPointRank(listOf(gen(1, 1)), pointSource = MergeInheritPointRank.ZERO_POINT_SOURCE)
        // inherit_earned = SUM(inherit_earned_act + inherit_earned_dyn)
        assertEquals(MergeInheritPointRank.TYPE_EARNED, result.earnedUpdate.targetType)
        assertEquals(
            listOf(MergeInheritPointRank.TYPE_EARNED_ACT, MergeInheritPointRank.TYPE_EARNED_DYN),
            result.earnedUpdate.sourceTypes,
        )
        // inherit_spent = inherit_spent_dyn
        assertEquals(MergeInheritPointRank.TYPE_SPENT, result.spentUpdate.targetType)
        assertEquals(listOf(MergeInheritPointRank.TYPE_SPENT_DYN), result.spentUpdate.sourceTypes)
    }

    @Test
    fun `the merge step deletes the existing inherit_earned_dyn rows before reinserting`() {
        val result = mergeInheritPointRank(listOf(gen(1, 1)), pointSource = MergeInheritPointRank.ZERO_POINT_SOURCE)
        assertEquals(MergeInheritPointRank.TYPE_EARNED_DYN, result.deleteType,
            "DELETE rank_data WHERE type = inherit_earned_dyn precedes the INSERT")
    }

    // ── empty world → empty fold, updates still issued ───────────────────────
    @Test
    fun `no generals yields no merge rows but still issues the derived updates`() {
        val result = mergeInheritPointRank(emptyList(), pointSource = MergeInheritPointRank.ZERO_POINT_SOURCE)
        assertTrue(result.mergeRows.isEmpty())
        assertEquals(MergeInheritPointRank.TYPE_EARNED, result.earnedUpdate.targetType)
    }

    // ── factory registration + leaf seam (month 1 / 7 / united) ──────────────
    @Test
    fun `MergeInheritPointRank is registered into the factory by name`() {
        val factory = EventActionFactory().also { MergeInheritPointRankAction.register(it) }
        assertTrue(factory.has("MergeInheritPointRank"))
        assertTrue(factory.create(RawAction("MergeInheritPointRank", emptyList())) is MergeInheritPointRankAction)
    }

    @Test
    fun `the leaf is a no-op without a world view (the daemon supplies it)`() {
        val ctx = object : EventActionContext { override val env = mapOf<String, Any?>("currentEventID" to 1) }
        MergeInheritPointRankAction().run(ctx) // must not throw
    }

    @Test
    fun `the leaf folds and applies through the world view`() {
        val applied = mutableListOf<MergeInheritResult>()
        val world = object : MergeInheritWorld {
            override fun mergeGenerals() = listOf(gen(1, 1), gen(2, 1))
            override fun inheritancePoints(key: String) = MergeInheritPointRank.ZERO_POINT_SOURCE(key)
            override fun applyMerge(result: MergeInheritResult) { applied.add(result) }
        }
        val ctx = object : EventActionContext {
            override val env = mapOf<String, Any?>("currentEventID" to 1, MergeInheritWorld.ENV_KEY to world)
        }
        MergeInheritPointRankAction().run(ctx)
        assertEquals(1, applied.size)
        assertEquals(2, applied[0].mergeRows.size, "one zero-value merge row per general")
        assertTrue(applied[0].mergeRows.all { it.value == 0 })
    }
}
