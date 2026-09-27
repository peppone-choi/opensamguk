package opensamguk.infra.persistence

import opensamguk.logic.record.AudienceTarget
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKey
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventPayloadCodec
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.GameEvent
import opensamguk.logic.record.OccurredAt
import opensamguk.logic.record.Publication
import opensamguk.logic.record.PublicationState
import opensamguk.logic.record.RefRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GameEventRowTest {
    private val at = OccurredAt(200, 2, 3, 9)
    private val key = EventKey.derive("test", "200", "2", "3", "9")
    private val privatePublication = Publication(PublicationState.PRIVATE)

    @Test
    fun `maps public event to published world row without private targets`() {
        val refs = mapOf(RefRole.CITY to EventRef.City(7), RefRole.FROM_NATION to EventRef.Nation(0),
            RefRole.TO_NATION to EventRef.Nation(3))
        val row = GameEventRow.from(GameEvent(1, EventKind.OWNER_CHANGED, at, AudienceTarget.Public,
            Publication(PublicationState.PUBLISHED), key, refs))
        assertEquals("county.ownerChanged", row.kind)
        assertEquals("WORLD", row.section)
        assertEquals("PUBLIC", row.audience)
        assertEquals("PUBLISHED", row.publicationState)
        assertEquals(9, row.occurredOrdinal)
        assertNull(row.audienceGeneralId)
        assertNull(row.audienceNationId)
        assertNull(row.recipientGeneralIds)
        assertEquals(refs, EventPayloadCodec.decodeRefs(row.refsJson))
        assertEquals("{}", row.factsJson)
    }

    @Test
    fun `maps private recipient snapshots in stable order and keeps income facts internal`() {
        val retinue = GameEventRow.from(GameEvent(1, EventKind.PEOPLE_JOINED, at,
            AudienceTarget.Retinue(7, 3, setOf(9, 2)), privatePublication, key,
            refs = mapOf(RefRole.PERSON to EventRef.General(9))))
        assertEquals(7, retinue.audienceGeneralId)
        assertEquals(3, retinue.audienceNationId)
        assertEquals(listOf(2, 9), retinue.recipientGeneralIds)
        assertEquals("PRIVATE", retinue.publicationState)

        val court = GameEventRow.from(GameEvent(1, EventKind.DISPATCH_RECEIVED, at,
            AudienceTarget.Court(3, setOf(9, 2)), privatePublication, key,
            refs = mapOf(RefRole.REQUEST to EventRef.Request("dispatch-7"))))
        assertNull(court.audienceGeneralId)
        assertEquals(3, court.audienceNationId)
        assertEquals(listOf(2, 9), court.recipientGeneralIds)

        val incomeFacts = mapOf(FactRole.MONEY to EventFact.Amount(900), FactRole.GRAIN to EventFact.Amount(500))
        val nation = GameEventRow.from(GameEvent(1, EventKind.INCOME_MONTHLY, at,
            AudienceTarget.Nation(3), privatePublication, key, facts = incomeFacts))
        assertNull(nation.audienceGeneralId)
        assertEquals(3, nation.audienceNationId)
        assertNull(nation.recipientGeneralIds)
        assertEquals(incomeFacts, EventPayloadCodec.decodeFacts(nation.factsJson))
    }
}
