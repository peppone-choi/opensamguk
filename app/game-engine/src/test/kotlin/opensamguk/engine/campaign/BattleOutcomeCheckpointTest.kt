package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class BattleOutcomeCheckpointTest {
    private fun observation(id: String): BattleOutcomeObservation = mock(BattleOutcomeObservation::class.java).also {
        `when`(it.worldId).thenReturn(1)
        `when`(it.encounterId).thenReturn(id)
    }

    @Test
    fun `failed unit observation suffix cannot publish after a later flush`() {
        val published = mutableListOf<CommittedBattleOutcomeBatch>()
        val buffer = BattleOutcomePostFlush { published += it }
        val before = observation("a".repeat(64))
        val failed = observation("b".repeat(64))
        buffer.onResolved(before)
        val checkpoint = buffer.checkpointUncommitted()
        buffer.onResolved(failed)

        buffer.restoreUncommitted(checkpoint)
        buffer.afterSuccessfulFlush(1, 1)
        assertEquals(listOf(before), published.single().observations)
        buffer.afterSuccessfulFlush(1, 2)
        assertEquals(1, published.size)
    }
}
