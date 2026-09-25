package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LordStatusTest {
    @Test fun `office and assets cannot grant lordship`() {
        assertFalse(LordStatus.read(mapOf("officerLevel" to 12, "counties" to 10)))
        assertTrue(LordStatus.read(mapOf("hwihaLord" to true)))
    }

    @Test fun `enlistment loses status while preserving all other metadata`() {
        val before = linkedMapOf<String, Any?>("hwihaLord" to true, "other" to listOf(1, 2))
        val after = LordStatus.afterEnlistment(before)
        assertEquals(mapOf("hwihaLord" to false, "other" to listOf(1, 2)), after)
        assertTrue(LordStatus.read(before))
        assertEquals(after, LordStatus.afterEnlistment(after))
    }

    @Test fun `malformed persisted status fails closed`() {
        for (bad in listOf(null, "true", 1)) {
            assertFailsWith<IllegalArgumentException> { LordStatus.read(mapOf("hwihaLord" to bad)) }
            assertFailsWith<IllegalArgumentException> { LordStatus.afterEnlistment(mapOf("hwihaLord" to bad)) }
        }
    }
}
