package opensamguk.gameapi.consistency

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadConsistencyBarrierTest {
    @Test
    fun `visible version returns success immediately`() {
        val barrier = ReadConsistencyBarrier(FakeWorldVersionReader(12), maxWaitMs = 0, pollMs = 1, retryAfterMs = 50)

        val result = barrier.await(12)

        assertTrue(result.visible)
        assertEquals(12L, result.currentVersion)
        assertEquals(12L, result.requiredVersion)
    }

    @Test
    fun `stale version returns VERSION_NOT_VISIBLE contract after bounded wait`() {
        val barrier = ReadConsistencyBarrier(FakeWorldVersionReader(7), maxWaitMs = 0, pollMs = 1, retryAfterMs = 50)

        val result = barrier.await(8)

        assertFalse(result.visible)
        assertEquals(7L, result.currentVersion)
        assertEquals(8L, result.requiredVersion)
        assertEquals(50L, result.retryAfterMs)
    }

    private class FakeWorldVersionReader(private val version: Long?) : WorldVersionReader {
        override fun currentWorldVersion(): Long? = version
    }
}
