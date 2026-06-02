package opensamguk.logic.inheritance

import opensamguk.common.constants.EffectiveGameConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [BuyHiddenBuffAction] — pins the PHP `BuyHiddenBuff.php` CUMULATIVE-DIFFERENCE cost
 * (`inheritBuffPoints[level] - inheritBuffPoints[prevLevel]`, direct index) and the
 * already-purchased / higher-grade guards. Fixes the prior absolute-cost + `level-1` off-by-one bug.
 */
class BuyHiddenBuffActionTest {

    /** Minimal in-memory [InheritanceStorage] seeded with a 'previous' (spendable) balance for u1. */
    private class FakeStorage(initialPrevious: Double) : InheritanceStorage {
        private val data = mutableMapOf("u1" to mutableMapOf("previous" to initialPrevious))
        override fun get(userId: String, key: String): Double? = data[userId]?.get(key)
        override fun set(userId: String, key: String, value: Double) {
            data.getOrPut(userId) { mutableMapOf() }[key] = value
        }
        override fun getAll(userId: String): Map<String, Double> = data[userId]?.toMap() ?: emptyMap()
        override fun clear(userId: String, keepKeys: Set<String>) {
            val m = data[userId] ?: return
            val kept = keepKeys.mapNotNull { k -> m[k]?.let { k to it } }.toMap()
            m.clear(); m.putAll(kept)
        }
    }

    private fun newAction(previous: Double): Pair<BuyHiddenBuffAction, FakeStorage> {
        val storage = FakeStorage(previous)
        val manager = InheritancePointManager(storage, EffectiveGameConst.of(emptyMap()))
        return BuyHiddenBuffAction(manager) { _, _, _ -> } to storage
    }

    @Test
    fun `fresh level 1 costs 200`() {
        val (action, _) = newAction(10_000.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 1))
        assertTrue(r.success, r.message)
        assertEquals(200.0, r.consumedPoints)
    }

    @Test
    fun `upgrade L1 to L2 charges the difference 400 not the absolute 600`() {
        val (action, _) = newAction(10_000.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 2, "prevLevel" to 1))
        assertTrue(r.success, r.message)
        assertEquals(400.0, r.consumedPoints)
    }

    @Test
    fun `fresh L2 with no prior level costs the full 600`() {
        val (action, _) = newAction(10_000.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 2))
        assertEquals(600.0, r.consumedPoints)
    }

    @Test
    fun `L5 from L4 charges 1000 difference`() {
        val (action, _) = newAction(10_000.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 5, "prevLevel" to 4))
        assertEquals(1000.0, r.consumedPoints) // 3000 - 2000
    }

    @Test
    fun `same level is already-purchased`() {
        val (action, _) = newAction(10_000.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 2, "prevLevel" to 2))
        assertFalse(r.success)
        assertEquals("이미 구입했습니다.", r.message)
    }

    @Test
    fun `lower than owned level is rejected`() {
        val (action, _) = newAction(10_000.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 1, "prevLevel" to 3))
        assertFalse(r.success)
        assertEquals("이미 더 높은 등급을 구입했습니다.", r.message)
    }

    @Test
    fun `insufficient previous balance fails`() {
        val (action, _) = newAction(100.0)
        val r = action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 1))
        assertFalse(r.success)
    }

    @Test
    fun `successful purchase deducts only the difference from previous`() {
        val (action, storage) = newAction(1_000.0)
        action.execute("u1", mapOf("buffKey" to "warAvoidRatio", "level" to 2, "prevLevel" to 1)) // cost 400
        assertEquals(600.0, storage.get("u1", "previous"))
    }
}
