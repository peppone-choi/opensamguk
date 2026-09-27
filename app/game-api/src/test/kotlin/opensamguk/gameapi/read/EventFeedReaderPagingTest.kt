package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventPayloadCodec
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer

class EventFeedReaderPagingTest {
    @Test
    fun `scan cap advances past hidden rows instead of replaying the same sparse page`() {
        val worlds = mock(WorldStateReadRepository::class.java)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 3))
        val rows = (4097L downTo 1L).map { id ->
            EventFeedRow(
                id = id,
                kind = if (id == 4097L) EventKind.OWNER_CHANGED.code else "retired.unknown",
                section = "WORLD", audience = "PUBLIC", audienceGeneralId = null,
                audienceNationId = null, recipientGeneralIds = emptySet(),
                year = 200, month = 1, phase = 1, ordinal = id.toInt(),
                refsJson = EventPayloadCodec.encodeRefs(mapOf(
                    RefRole.CITY to EventRef.City(10),
                    RefRole.FROM_NATION to EventRef.Nation(1),
                    RefRole.TO_NATION to EventRef.Nation(2),
                )), factsJson = EventPayloadCodec.encodeFacts(emptyMap()), publicationState = "PUBLISHED",
                hasDelayedPublication = false,
            )
        }
        val events = mock(EventFeedReadRepository::class.java, Answer { invocation ->
            val before = invocation.arguments[1] as EventFeedPosition?
            val limit = invocation.arguments[2] as Int
            rows.asSequence().filter { before == null || it.id < before.id }.take(limit).toList()
        })
        val reader = EventFeedReader(worlds, mock(GeneralResolver::class.java),
            mock(SecretPermissionReader::class.java), events)

        val first = reader.publicFeed(null, 2)
        assertEquals(listOf(4097L), first.events.map { it.id })
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(2L, EventFeedCursor.decode(cursor, 3,
            opensamguk.logic.record.EventSection.WORLD)?.id)

        val second = reader.publicFeed(cursor, 2)
        assertEquals(emptyList(), second.events)
        assertNull(second.nextCursor)
    }
}
