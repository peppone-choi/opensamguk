package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HwihaLordStatusTest {
    @Test fun `office and assets cannot grant lordship`() {
        assertFalse(HwihaLordStatus.read(mapOf("officerLevel" to 12, "counties" to 10)))
        assertTrue(HwihaLordStatus.read(mapOf("hwihaLord" to true)))
    }

    @Test fun `enlistment loses status while preserving all other metadata`() {
        val before = linkedMapOf<String, Any?>("hwihaLord" to true, "other" to listOf(1, 2))
        val after = HwihaLordStatus.afterEnlistment(before)
        assertEquals(mapOf("hwihaLord" to false, "other" to listOf(1, 2)), after)
        assertTrue(HwihaLordStatus.read(before))
        assertEquals(after, HwihaLordStatus.afterEnlistment(after))
    }

    @Test fun `malformed persisted status fails closed`() {
        for (bad in listOf(null, "true", 1)) {
            assertFailsWith<IllegalArgumentException> { HwihaLordStatus.read(mapOf("hwihaLord" to bad)) }
            assertFailsWith<IllegalArgumentException> { HwihaLordStatus.afterEnlistment(mapOf("hwihaLord" to bad)) }
        }
    }
}
