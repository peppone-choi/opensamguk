package opensamguk.logic.input

import kotlin.test.*

class HwihaPersonPolicyStateTest {
    private val state = HwihaPersonPolicyState(30, false, "fixture", "pin", 0)
    @Test fun `metadata roundtrip preserves explicit zero false and absence`() {
        assertNull(HwihaPersonPolicyState.read(emptyMap()))
        val zero = state.copy(renownCapacity = 0)
        assertEquals(zero, HwihaPersonPolicyState.read(mapOf(HwihaPersonPolicyState.META_KEY to zero.toMetaValue())))
    }
    @Test fun `malformed and default-like values never coerce to policy`() {
        for (invalid in listOf(null, 30, "policy")) {
            assertFailsWith<IllegalArgumentException> { HwihaPersonPolicyState.read(mapOf(HwihaPersonPolicyState.META_KEY to invalid)) }
        }
        for ((field, invalid) in listOf(
            "renownCapacity" to -1, "renownCapacity" to 30L, "renownCapacity" to 30.0,
            "officerId" to -1, "officerId" to "1", "acceptsEnlistment" to 1,
            "statSourceId" to " ", "statSourceRevision" to "", "officerId" to null,
        )) {
            val value = state.toMetaValue() + (field to invalid)
            assertFailsWith<IllegalArgumentException> { HwihaPersonPolicyState.read(mapOf(HwihaPersonPolicyState.META_KEY to value)) }
        }
        for (value in listOf(state.toMetaValue() - "officerId", state.toMetaValue() + ("stats" to 50))) {
            assertFailsWith<IllegalArgumentException> { HwihaPersonPolicyState.read(mapOf(HwihaPersonPolicyState.META_KEY to value)) }
        }
    }
}
