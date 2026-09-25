package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class CourtQueueTest {
    private val queue = QueuedDispatch("request._:-123", 42, 2, 10)
    private fun read(value: Any?) = QueuedDispatch.read(mapOf(QueuedDispatch.META_KEY to value))

    @Test fun `round trip preserves owner and target with no actor field`() {
        assertEquals(queue, read(queue.toMetaValue()))
        assertEquals(setOf("requestId", "ownerUserId", "targetGeneralId", "countyId"), queue.toMetaValue().keys)
        assertEquals(queue.copy(requestId = "a".repeat(128)), read(queue.copy(requestId = "a".repeat(128)).toMetaValue()))
    }

    @Test fun `only absent key is empty queue`() {
        assertNull(QueuedDispatch.read(mapOf("unrelated" to 1)))
        for (value in listOf(null, false, "", 1, emptyList<Any>(), emptyMap<String, Any>())) {
            assertFailsWith<IllegalArgumentException> { read(value) }
        }
    }

    @Test fun `missing unknown and actor fields fail closed`() {
        for (key in queue.toMetaValue().keys) {
            assertFailsWith<IllegalArgumentException> { read(queue.toMetaValue() - key) }
        }
        for (key in listOf("actorId", "unexpected")) {
            assertFailsWith<IllegalArgumentException> { read(queue.toMetaValue() + (key to 1)) }
        }
    }

    @Test fun `IDs require positive integer types without coercion`() {
        for (key in listOf("ownerUserId", "targetGeneralId", "countyId")) {
            for (bad in listOf(null, 0, -1, 1L, 1.0, "1", true)) {
                assertFailsWith<IllegalArgumentException> { read(queue.toMetaValue() + (key to bad)) }
            }
        }
    }

    @Test fun `request identity is bounded ASCII and required string`() {
        for (bad in listOf(null, 1, "", "a".repeat(129), "a b", "한", "a/b", "a\n")) {
            assertFailsWith<IllegalArgumentException> { read(queue.toMetaValue() + ("requestId" to bad)) }
        }
    }
}
