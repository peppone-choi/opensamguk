package opensamguk.logic.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class OfflineDelegationEventPrivacyTest {
    @Test
    fun `delegation lifecycle events accept only the beneficiary self audience`() {
        for (kind in listOf(EventKind.OFFLINE_DELEGATION_STARTED, EventKind.OFFLINE_DELEGATION_ENDED)) {
            assertEquals(setOf(EventAudience.SELF), kind.audiences)
            assertFalse(kind in EventKind.publicKinds)
            assertFailsWith<IllegalArgumentException> {
                GameEvent(
                    worldId = 1,
                    kind = kind,
                    occurredAt = OccurredAt(190, 1, 1, 0),
                    audience = AudienceTarget.Nation(3),
                    publication = Publication(PublicationState.PRIVATE),
                    eventKey = EventKey.derive(kind.code, "foreign"),
                )
            }
        }
    }
}
