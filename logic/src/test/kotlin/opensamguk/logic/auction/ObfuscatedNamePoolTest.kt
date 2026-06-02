package opensamguk.logic.auction

import opensamguk.common.constants.GameConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObfuscatedNamePoolTest {

    @Test
    fun `pool size equals first times middle times last`() {
        val expected = GameConst.randGenFirstName.size *
            GameConst.randGenMiddleName.size *
            GameConst.randGenLastName.size
        assertEquals(expected, ObfuscatedNamePool.poolSize)
    }

    @Test
    fun `buildPool produces deterministic shuffle for same seed`() {
        val seed = "test_seed_123"
        val poolA = ObfuscatedNamePool.buildPool(seed)
        val poolB = ObfuscatedNamePool.buildPool(seed)
        assertEquals(poolA, poolB)
    }

    @Test
    fun `buildPool produces different shuffle for different seeds`() {
        val poolA = ObfuscatedNamePool.buildPool("seed_a")
        val poolB = ObfuscatedNamePool.buildPool("seed_b")
        assertTrue(poolA != poolB, "different seeds should yield different shuffles")
    }

    @Test
    fun `decode id 0 returns first element of pool`() {
        val pool = ObfuscatedNamePool.buildPool("seed")
        assertEquals(pool[0], ObfuscatedNamePool.decode(0, pool))
    }

    @Test
    fun `decode with id less than pool size returns pool element without suffix`() {
        val pool = ObfuscatedNamePool.buildPool("seed")
        val id = pool.size - 1
        assertEquals(pool[id], ObfuscatedNamePool.decode(id, pool))
    }

    @Test
    fun `decode with id equal to pool size returns first element with dupIdx 1`() {
        val pool = listOf("가가", "간간", "감감")
        assertEquals("가가1", ObfuscatedNamePool.decode(3, pool))
    }

    @Test
    fun `decode with id greater than pool size appends dupIdx`() {
        val pool = listOf("가가", "간간", "감감")
        // id = 7 => dupIdx = 2, subIdx = 1
        assertEquals("간간2", ObfuscatedNamePool.decode(7, pool))
    }

    @Test
    fun `decode large id matches PHP intdiv and modulo formula`() {
        val pool = listOf("가가", "간간", "감감")
        // id = 10 => dupIdx = 3, subIdx = 1
        assertEquals("간간3", ObfuscatedNamePool.decode(10, pool))
    }
}
