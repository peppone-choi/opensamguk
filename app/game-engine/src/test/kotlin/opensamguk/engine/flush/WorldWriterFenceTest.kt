package opensamguk.engine.flush

import opensamguk.common.world.WorldId
import kotlin.test.Test
import kotlin.test.assertEquals

class WorldWriterFenceTest {
    @Test
    fun `nextExpectedAfterCommit advances by one`() {
        val fence = WorldWriterFence(WorldId(1), writerEpoch = 3L, expectedWorldVersion = 10L)
        assertEquals(11L, fence.nextExpectedAfterCommit())
    }
}
