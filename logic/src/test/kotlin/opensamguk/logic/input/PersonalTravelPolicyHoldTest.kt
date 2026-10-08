package opensamguk.logic.input

import kotlin.test.*

class PersonalTravelPolicyHoldTest {
    @Test fun `policy hold restores only strict valid records`() {
        val held = PersonalTravelPolicyHold("old-order", TravelFailure.TRAVEL_POLICY_CHANGED, Phase(200, 1, 1))
        val raw = held.toMetaValue()
        assertEquals(held, PersonalTravelPolicyHold.read(mapOf(PersonalTravelPolicyHold.META_KEY to raw)))
        assertNull(PersonalTravelPolicyHold.read(emptyMap()))
        for (invalid in listOf(null, raw + ("version" to 2), raw + ("extra" to true),
                raw + ("orderId" to ""), raw + ("reason" to TravelFailure.NO_ROUTE.name)))
            assertFailsWith<IllegalArgumentException> { PersonalTravelPolicyHold.read(mapOf(PersonalTravelPolicyHold.META_KEY to invalid)) }
    }
}
