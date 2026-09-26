package opensamguk.logic.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EventOrdinalAllocatorTest {
    @Test
    fun `multiple flushes in one phase continue from committed maximum`() {
        val turn = EventTurn(200, 2, 3)
        val allocator = EventOrdinalAllocator(turn, lastCommittedOrdinal = 7)
        assertEquals(8, allocator.allocate(turn).ordinal)
        assertEquals(9, allocator.allocate(turn).ordinal)
        val restarted = EventOrdinalAllocator(turn, lastCommittedOrdinal = 9)
        assertEquals(10, restarted.allocate(turn).ordinal)
    }

    @Test
    fun `new phase starts at zero and backwards time fails closed`() {
        val allocator = EventOrdinalAllocator(EventTurn(200, 12, 3))
        assertEquals(0, allocator.allocate(EventTurn(200, 12, 3)).ordinal)
        assertEquals(0, allocator.allocate(EventTurn(201, 1, 1)).ordinal)
        assertFailsWith<IllegalArgumentException> { allocator.allocate(EventTurn(200, 12, 3)) }
    }

    @Test
    fun `rejects impossible dates and invalid committed maximum`() {
        assertFailsWith<IllegalArgumentException> { EventTurn(200, 13, 1) }
        assertFailsWith<IllegalArgumentException> { EventOrdinalAllocator(EventTurn(200, 1, 1), -2) }
    }
}
