package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import opensamguk.logic.record.EventFact
import opensamguk.logic.record.EventKind
import opensamguk.logic.record.EventPayloadCodec
import opensamguk.logic.record.EventRef
import opensamguk.logic.record.EventSection
import opensamguk.logic.record.FactRole
import opensamguk.logic.record.RefRole
import opensamguk.logic.record.RewardReasonCode
import opensamguk.logic.renown.RenownEventSource
import org.springframework.web.server.ResponseStatusException

class EventFeedPolicyTest {
    @Test
    fun `personal history survives transfer while the former nation's shared history closes`() {
        val personal = row(EventKind.MARCH_ASSIGNMENT, "SELF", general = 7)
        val retinue = row(EventKind.PEOPLE_JOINED, "RETINUE", general = 7, nation = 3,
            recipients = setOf(7, 9), refs = mapOf(RefRole.PERSON to EventRef.General(9)))
        val nation = row(EventKind.INCOME_MONTHLY, "NATION", nation = 3,
            facts = mapOf(FactRole.MONEY to EventFact.Amount(100)))
        val court = row(EventKind.DISPATCH_RECEIVED, "COURT", nation = 3, recipients = setOf(7),
            refs = mapOf(RefRole.REQUEST to EventRef.Request("req-1"),
                RefRole.TARGET to EventRef.General(7)))
        val public = row(EventKind.OWNER_CHANGED, "PUBLIC", published = true,
            refs = mapOf(RefRole.CITY to EventRef.City(11), RefRole.FROM_NATION to EventRef.Nation(3),
                RefRole.TO_NATION to EventRef.Nation(4)))

        assertNotNull(EventFeedPolicy.project(personal, 7, 4, 0))
        assertNull(EventFeedPolicy.project(retinue, 7, 4, 4))
        assertNull(EventFeedPolicy.project(nation, 7, 4, 4))
        assertNull(EventFeedPolicy.project(court, 7, 4, 4))
        assertNotNull(EventFeedPolicy.project(public, null, 0, -1))
    }

    @Test
    fun `retinue snapshot and current secret permission are both necessary`() {
        val event = row(EventKind.PEOPLE_JOINED, "RETINUE", general = 7, nation = 3,
            recipients = setOf(7, 9), refs = mapOf(RefRole.PERSON to EventRef.General(9)))
        assertNotNull(EventFeedPolicy.project(event, 9, 3, 1))
        assertNull(EventFeedPolicy.project(event, 10, 3, 4))
        assertNull(EventFeedPolicy.project(event, 9, 3, 0))
        assertNull(EventFeedPolicy.project(event.copy(audienceNationId = null), 9, 3, 4))
    }

    @Test
    fun `court directly involved person needs nonnegative permission and other recipient needs two`() {
        val event = row(EventKind.DISPATCH_ISSUED, "COURT", nation = 3, recipients = setOf(7, 9),
            refs = mapOf(RefRole.REQUEST to EventRef.Request("req-1"),
                RefRole.ISSUER to EventRef.General(7)))
        assertNotNull(EventFeedPolicy.project(event, 7, 3, 0))
        assertNull(EventFeedPolicy.project(event, 7, 3, -1))
        assertNull(EventFeedPolicy.project(event, 9, 3, 1))
        assertNull(EventFeedPolicy.project(event, 9, 3, 2, officerLevel = 0))
        assertNotNull(EventFeedPolicy.project(event, 9, 3, 2, officerLevel = 5))
    }

    @Test
    fun `military shared event needs two and does not project unverified map or corps refs`() {
        val event = row(EventKind.MARCH_CORPS, "RETINUE", general = 7, nation = 3,
            recipients = setOf(7, 9), refs = mapOf(RefRole.ACTOR to EventRef.General(7),
                RefRole.CITY to EventRef.City(11), RefRole.CORPS to EventRef.Corps("corps-1")))
        assertNull(EventFeedPolicy.project(event, 9, 3, 1))
        val projected = assertNotNull(EventFeedPolicy.project(event, 9, 3, 2))
        assertEquals(emptyMap(), projected.refs)
    }

    @Test
    fun `malformed payload and wrong publication are not projected`() {
        val event = row(EventKind.OWNER_CHANGED, "PUBLIC", published = true,
            refs = mapOf(RefRole.CITY to EventRef.City(11), RefRole.FROM_NATION to EventRef.Nation(3),
                RefRole.TO_NATION to EventRef.Nation(4)))
        assertNull(EventFeedPolicy.project(event.copy(refsJson = "{\"CITY\":\"<script>\"}"), null, 0, -1))
        assertNull(EventFeedPolicy.project(event.copy(publicationState = "PRIVATE"), null, 0, -1))
        assertNull(EventFeedPolicy.project(event.copy(hasDelayedPublication = true), null, 0, -1))
    }

    @Test
    fun `cursor is fixed to world and section`() {
        val position = EventFeedPosition(200, 12, 3, 51, 99)
        val cursor = EventFeedCursor.encode(3, EventSection.BATTLE, position)
        assertEquals(position, EventFeedCursor.decode(cursor, 3, EventSection.BATTLE))
        assertFailsWith<ResponseStatusException> { EventFeedCursor.decode(cursor, 4, EventSection.BATTLE) }
        assertFailsWith<ResponseStatusException> { EventFeedCursor.decode(cursor, 3, EventSection.WORLD) }
        assertFailsWith<ResponseStatusException> { EventFeedCursor.decode("not a cursor", 3, EventSection.BATTLE) }
    }

    @Test
    fun `reward receipt is personal and requires its amount and enumerated reason`() {
        val refs = mapOf(RefRole.ISSUER to EventRef.General(3), RefRole.TARGET to EventRef.General(7))
        val facts = mapOf(FactRole.MONEY to EventFact.Amount(50),
            FactRole.REASON to EventFact.RewardReason(RewardReasonCode.WAR_MERIT))
        val row = row(EventKind.REWARD_RECEIVED, "SELF", general = 7, refs = refs, facts = facts)
        assertEquals("WAR_MERIT", EventFeedPolicy.project(row, 7, 1, 0)?.facts?.get("REASON"))
        assertNull(EventFeedPolicy.project(row, 3, 1, 4))
        assertNull(EventFeedPolicy.project(row.copy(refsJson = EventPayloadCodec.encodeRefs(
            refs + (RefRole.TARGET to EventRef.General(8)))), 7, 1, 0))
        assertNull(EventFeedPolicy.project(row.copy(factsJson = EventPayloadCodec.encodeFacts(facts - FactRole.REASON)), 7, 1, 0))
    }

    @Test
    fun `renown source is visible only to the same personal actor`() {
        val row = row(EventKind.RENOWN_EVENT, "SELF", general = 7,
            refs = mapOf(RefRole.ACTOR to EventRef.General(7)),
            facts = mapOf(FactRole.SOURCE to EventFact.RenownSource(RenownEventSource.COUNTY_CAPTURE)))
        assertEquals("COUNTY_CAPTURE", EventFeedPolicy.project(row, 7, 1, 0)?.facts?.get("SOURCE"))
        assertNull(EventFeedPolicy.project(row.copy(refsJson = EventPayloadCodec.encodeRefs(
            mapOf(RefRole.ACTOR to EventRef.General(8)))), 7, 1, 0))
    }

    private fun row(kind: EventKind, audience: String, general: Int? = null, nation: Int? = null,
                    recipients: Set<Int> = emptySet(), published: Boolean = false,
                    refs: Map<RefRole, EventRef> = emptyMap(),
                    facts: Map<FactRole, EventFact> = emptyMap()) = EventFeedRow(
        id = 1, kind = kind.code, section = kind.section.name, audience = audience,
        audienceGeneralId = general, audienceNationId = nation, recipientGeneralIds = recipients,
        year = 200, month = 1, phase = 1, ordinal = 0,
        refsJson = EventPayloadCodec.encodeRefs(refs), factsJson = EventPayloadCodec.encodeFacts(facts),
        publicationState = if (published) "PUBLISHED" else "PRIVATE", hasDelayedPublication = false,
    )
}
