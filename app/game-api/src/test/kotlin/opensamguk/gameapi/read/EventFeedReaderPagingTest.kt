package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.logic.record.EventSection
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventPayloadCodec
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.RefRole
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.stubbing.Answer
import org.springframework.web.server.ResponseStatusException

class EventFeedReaderPagingTest {
    @Test
    fun `city feed pages only projected city refs after audience policy`() {
        val rows = listOf(
            personalRow(5, 11, 7), personalRow(4, 12, 7),
            personalRow(3, 11, 8), personalRow(2, 11, 7),
        )
        val reader = privateReader(rows, 11)
        val first = reader.privateFeed(77, EventSection.PERSONAL, null, 1, 11)
        assertEquals(listOf(5L), first.events.map { it.id })
        assertEquals(11, first.events.single().refs[RefRole.CITY.name])
        val cursor = assertNotNull(first.nextCursor)
        assertEquals(5L, EventFeedCursor.decode(cursor, 3, EventSection.PERSONAL, 11)?.id)
        val second = reader.privateFeed(77, EventSection.PERSONAL, cursor, 1, 11)
        assertEquals(listOf(2L), second.events.map { it.id })
        assertNull(second.nextCursor)
        assertFailsWith<ResponseStatusException> { reader.privateFeed(77, EventSection.PERSONAL, null, 1, 0) }
    }

    @Test
    fun `city feed does not reveal masked battle location or hidden scan position`() {
        val rows = (4096L downTo 1L).map { id ->
            personalRow(id, 11, 7).copy(kind = EventKind.MARCH_DIRECT.code, section = "BATTLE")
        }
        val page = privateReader(rows, 11).privateFeed(77, EventSection.BATTLE, null, 1, 11)
        assertEquals(emptyList(), page.events)
        assertNull(page.nextCursor)
    }

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

    private fun privateReader(rows: List<EventFeedRow>, cityId: Int): EventFeedReader {
        val worlds = mock(WorldStateReadRepository::class.java)
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 3))
        val resolver = mock(GeneralResolver::class.java)
        val me = GeneralResolver.ResolvedGeneral(GeneralReadEntity(id = 7, worldId = 3,
            nationId = 3, officerLevel = 5), 5, 2, 3, 0)
        `when`(resolver.resolve(77)).thenReturn(me)
        val permission = mock(SecretPermissionReader::class.java)
        `when`(permission.of(me)).thenReturn(2)
        val events = mock(EventFeedReadRepository::class.java, Answer { invocation ->
            assertEquals(cityId, invocation.arguments[7])
            val before = invocation.arguments[5] as EventFeedPosition?
            val limit = invocation.arguments[6] as Int
            rows.asSequence().filter { before == null || it.id < before.id }.take(limit).toList()
        })
        return EventFeedReader(worlds, resolver, permission, events)
    }

    private fun personalRow(id: Long, cityId: Int, generalId: Int): EventFeedRow = EventFeedRow(
        id = id, kind = EventKind.MARCH_ASSIGNMENT.code, section = "PERSONAL", audience = "SELF",
        audienceGeneralId = generalId, audienceNationId = null, recipientGeneralIds = emptySet(),
        year = 200, month = 1, phase = 1, ordinal = id.toInt(),
        refsJson = EventPayloadCodec.encodeRefs(mapOf(RefRole.CITY to EventRef.City(cityId))),
        factsJson = EventPayloadCodec.encodeFacts(emptyMap()), publicationState = "PRIVATE",
        hasDelayedPublication = false,
    )
}
