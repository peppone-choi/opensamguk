package opensamguk.logic.record

import opensamguk.logic.input.RecordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class GameEventTest {
    private val whenOccurred = OccurredAt(200, 2, 3, 0)
    private val key = EventKey.derive("turn", "200", "2", "3", "actor-1")

    @Test
    fun `all 31 existing record kinds have a new classification`() {
        val legacy = RecordKind::class.java.declaredFields
            .filter { it.type == String::class.java && it.name != "REFS_META_KEY" }
            .map { it.get(null) as String }.toSet()
        assertEquals(31, legacy.size)
        assertEquals(legacy, EventKind.entries.map { it.code }.toSet() - EventKind.OWNER_CHANGED.code)
        assertEquals(5, EventKind.entries.map { it.section }.toSet().size)
    }

    @Test
    fun `public ownership change has one canonical kind and only safe refs`() {
        val refs = mapOf(
            RefRole.CITY to EventRef.City(17),
            RefRole.FROM_NATION to EventRef.Nation(0),
            RefRole.TO_NATION to EventRef.Nation(3),
        )
        val event = GameEvent(1, EventKind.OWNER_CHANGED, whenOccurred, AudienceTarget.Public,
            Publication(PublicationState.PUBLISHED), key, refs)
        assertEquals(EventSection.WORLD, event.section)
        assertFailsWith<IllegalArgumentException> {
            event.copy(refs = refs + (RefRole.ACTOR to EventRef.General(2)))
        }
        assertFailsWith<IllegalArgumentException> {
            event.copy(refs = refs - RefRole.FROM_NATION)
        }
        assertFailsWith<IllegalArgumentException> {
            event.copy(facts = mapOf(FactRole.AMOUNT to EventFact.Amount(900)))
        }
        assertFailsWith<IllegalArgumentException> {
            event.copy(publication = Publication(PublicationState.PRIVATE))
        }
    }

    @Test
    fun `private income and court dispatch cannot be published`() {
        val income = GameEvent(1, EventKind.INCOME_MONTHLY, whenOccurred, AudienceTarget.Nation(2),
            Publication(PublicationState.PRIVATE), key,
            facts = mapOf(FactRole.AMOUNT to EventFact.Amount(900)))
        assertEquals(EventSection.RETINUE_NATION, income.section)
        assertFailsWith<IllegalArgumentException> {
            income.copy(audience = AudienceTarget.Public, publication = Publication(PublicationState.PUBLISHED))
        }
        assertFailsWith<IllegalArgumentException> {
            income.copy(facts = mapOf(FactRole.OUTCOME to EventFact.Outcome("secret")))
        }
        val dispatch = GameEvent(1, EventKind.DISPATCH_RECEIVED, whenOccurred,
            AudienceTarget.Court(2, setOf(5, 8)), Publication(PublicationState.PRIVATE), key,
            refs = mapOf(RefRole.REQUEST to EventRef.Request("dispatch-7")))
        assertEquals(EventSection.COURT, dispatch.section)
        assertFailsWith<IllegalArgumentException> { dispatch.copy(refs = emptyMap()) }
        assertFailsWith<IllegalArgumentException> {
            dispatch.copy(refs = mapOf(RefRole.REQUEST to EventRef.City(7)))
        }
    }

    @Test
    fun `event keys are stable and time and recipient contracts reject invalid values`() {
        assertEquals(key, EventKey.derive("turn", "200", "2", "3", "actor-1"))
        assertNotNull(EventKind.fromCode("county.ownerChanged"))
        assertFailsWith<IllegalArgumentException> { EventKey.derive("rendered sentence") }
        assertFailsWith<IllegalArgumentException> { OccurredAt(200, 13, 1, 0) }
        assertFailsWith<IllegalArgumentException> { OccurredAt(200, 1, 4, 0) }
        assertFailsWith<IllegalArgumentException> { AudienceTarget.Retinue(1, emptySet()) }
        assertFailsWith<IllegalArgumentException> { Publication(PublicationState.PRIVATE, whenOccurred) }
    }

    @Test
    fun `JSONB wire round trips typed values and rejects arbitrary payloads`() {
        val refs = mapOf(RefRole.CITY to EventRef.City(7), RefRole.FROM_NATION to EventRef.Nation(0),
            RefRole.REQUEST to EventRef.Request("dispatch-7"))
        assertEquals(refs, EventPayloadCodec.decodeRefs(EventPayloadCodec.encodeRefs(refs)))
        val facts = mapOf(FactRole.AMOUNT to EventFact.Amount(900), FactRole.OUTCOME to EventFact.Outcome("won"))
        assertEquals(facts, EventPayloadCodec.decodeFacts(EventPayloadCodec.encodeFacts(facts)))
        assertFailsWith<IllegalArgumentException> { EventPayloadCodec.decodeRefs("""{"NAME":"허도"}""") }
        assertFailsWith<IllegalArgumentException> { EventPayloadCodec.decodeRefs("""{"CITY":"허도"}""") }
        assertFailsWith<IllegalArgumentException> { EventPayloadCodec.decodeFacts("""{"AMOUNT":"900"}""") }
    }
}
