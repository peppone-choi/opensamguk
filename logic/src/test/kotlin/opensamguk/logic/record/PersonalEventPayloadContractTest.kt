package opensamguk.logic.record

import opensamguk.logic.input.PersonalInput
import opensamguk.logic.input.TrainingStat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PersonalEventPayloadContractTest {
    private val changes = setOf(FactRole.STAT_CHANGE, FactRole.FATIGUE_CHANGE, FactRole.INJURY_CHANGE,
        FactRole.EXPERIENCE_CHANGE, FactRole.DEDICATION_CHANGE)
    private val identifiers = setOf(FactRole.COMMAND, FactRole.TRAINING_STAT)

    private fun event(kind: EventKind, facts: Map<FactRole, EventFact> = emptyMap()) =
        GameEvent(1, kind, OccurredAt(200, 2, 3, 0), AudienceTarget.Self(7),
            Publication(PublicationState.PRIVATE), EventKey.derive("personal-contract", "actor-7"),
            refs = mapOf(RefRole.ACTOR to EventRef.General(7)), facts = facts)

    @Test
    fun `catalog preserves old optional facts and personal audience and refs`() {
        assertEquals(identifiers + changes, EventKind.PERSONAL_APPLIED.allowedFacts)
        assertEquals(setOf(FactRole.COMMAND, FactRole.OUTCOME), EventKind.INPUT_REJECTED.allowedFacts)
        for (kind in listOf(EventKind.PERSONAL_APPLIED, EventKind.INPUT_REJECTED)) {
            assertTrue(kind.requiredFacts.isEmpty())
            assertTrue(kind.requiredRefs.isEmpty())
            assertEquals(setOf(RefRole.ACTOR), kind.allowedRefs)
            assertEquals(setOf(EventAudience.SELF), kind.audiences)
            assertEquals(EventSection.PERSONAL, kind.section)
            assertEquals(emptyMap(), event(kind).facts)
            assertEquals(emptyMap(), event(kind).copy(refs = emptyMap()).refs)
            assertEquals("{}", EventPayloadCodec.encodeFacts(event(kind).facts))
            assertEquals(event(kind), event(kind).copy(facts = EventPayloadCodec.decodeFacts("{}")))
        }
    }

    @Test
    fun `canonical training recuperation and travel facts round trip without prose`() {
        val examples = listOf(
            linkedMapOf(FactRole.COMMAND to EventFact.Outcome(PersonalInput.SELF_TRAIN),
                FactRole.TRAINING_STAT to EventFact.Outcome(TrainingStat.STRENGTH.wireName),
                FactRole.STAT_CHANGE to EventFact.Change(1), FactRole.FATIGUE_CHANGE to EventFact.Change(0)),
            linkedMapOf(FactRole.COMMAND to EventFact.Outcome(PersonalInput.RECUPERATE),
                FactRole.INJURY_CHANGE to EventFact.Change(-3), FactRole.FATIGUE_CHANGE to EventFact.Change(-5)),
            linkedMapOf(FactRole.COMMAND to EventFact.Outcome(PersonalInput.TRAVEL),
                FactRole.EXPERIENCE_CHANGE to EventFact.Change(10), FactRole.DEDICATION_CHANGE to EventFact.Change(1)),
        )
        for (facts in examples) {
            val applied = event(EventKind.PERSONAL_APPLIED, facts)
            assertEquals(facts, EventPayloadCodec.decodeFacts(EventPayloadCodec.encodeFacts(applied.facts)))
        }
        for (stat in TrainingStat.entries) {
            val facts = mapOf(FactRole.TRAINING_STAT to EventFact.Outcome(stat.wireName))
            assertEquals(facts, EventPayloadCodec.decodeFacts(EventPayloadCodec.encodeFacts(facts)))
        }
    }

    @Test
    fun `rejection carries command and actual code without applied effects`() {
        val facts = linkedMapOf(FactRole.COMMAND to EventFact.Outcome(PersonalInput.RECUPERATE),
            FactRole.OUTCOME to EventFact.Outcome("ALREADY_HEALTHY"))
        val rejected = event(EventKind.INPUT_REJECTED, facts)
        assertEquals(facts, EventPayloadCodec.decodeFacts(EventPayloadCodec.encodeFacts(rejected.facts)))
        for (role in changes) {
            assertFailsWith<IllegalArgumentException> { rejected.copy(facts = facts + (role to EventFact.Change(0))) }
        }
        assertFailsWith<IllegalArgumentException> {
            rejected.copy(facts = facts + (FactRole.TRAINING_STAT to EventFact.Outcome("strength")))
        }
        assertFailsWith<IllegalArgumentException> { event(EventKind.PERSONAL_APPLIED, facts) }
    }

    @Test
    fun `new roles retain signed Long changes including zero and both boundaries`() {
        for (role in changes) {
            for (value in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                val facts = mapOf(role to EventFact.Change(value))
                assertEquals(facts, event(EventKind.PERSONAL_APPLIED, facts).facts)
                assertEquals(facts, EventPayloadCodec.decodeFacts("""{"${role.name}":$value}"""))
            }
            assertEquals(mapOf(role to EventFact.Change(100)),
                EventPayloadCodec.decodeFacts("""{"${role.name}":1e2}"""))
        }
    }

    @Test
    fun `event validation rejects incorrect fact classes and unrelated kinds`() {
        for (role in identifiers) {
            assertFailsWith<IllegalArgumentException> {
                event(EventKind.PERSONAL_APPLIED, mapOf(role to EventFact.Change(1)))
            }
        }
        for (role in changes) {
            assertFailsWith<IllegalArgumentException> {
                event(EventKind.PERSONAL_APPLIED, mapOf(role to EventFact.Amount(1)))
            }
        }
        val command = mapOf(FactRole.COMMAND to EventFact.Outcome(PersonalInput.TRAVEL))
        assertFailsWith<IllegalArgumentException> { event(EventKind.FIELD_APPLIED, command) }
        assertFailsWith<IllegalArgumentException> { event(EventKind.PERSONAL_APPLIED, command)
            .copy(audience = AudienceTarget.Public, publication = Publication(PublicationState.PUBLISHED)) }
    }

    @Test
    fun `codec requires stable string identifiers and integer change scalars`() {
        val commonInvalid = listOf("null", "true", "[]", "{}")
        for (role in identifiers) {
            for (raw in commonInvalid + listOf("1", "1.0", "\"\"", "\"개인 행동 완료\"", "\"action/travel\"")) {
                assertFailsWith<IllegalArgumentException>("${role.name}:$raw") {
                    EventPayloadCodec.decodeFacts("""{"${role.name}":$raw}""")
                }
            }
        }
        for (role in changes) {
            for (raw in commonInvalid + listOf("\"0\"", "\"-5\"", "1.5",
                "9223372036854775808", "-9223372036854775809")) {
                assertFailsWith<IllegalArgumentException>("${role.name}:$raw") {
                    EventPayloadCodec.decodeFacts("""{"${role.name}":$raw}""")
                }
            }
        }
    }

    @Test
    fun `old payloads and unknown role rejection retain their wire contract`() {
        val oldWire = """{"MONEY":900,"RENOWN_CHANGE":-5,"OUTCOME":"won"}"""
        val oldFacts = linkedMapOf(FactRole.MONEY to EventFact.Amount(900),
            FactRole.RENOWN_CHANGE to EventFact.Change(-5), FactRole.OUTCOME to EventFact.Outcome("won"))
        assertEquals(oldFacts, EventPayloadCodec.decodeFacts(oldWire))
        assertEquals(oldWire, EventPayloadCodec.encodeFacts(oldFacts))
        for (wire in listOf("""{"UNKNOWN_CHANGE":1}""", """{"MONEY":"900"}""", """{"MONEY":-1}""")) {
            assertFailsWith<IllegalArgumentException> { EventPayloadCodec.decodeFacts(wire) }
        }
    }

    @Test
    fun `ordered source facts serialize deterministically with exact role names`() {
        val facts = linkedMapOf(FactRole.COMMAND to EventFact.Outcome(PersonalInput.SELF_TRAIN),
            FactRole.TRAINING_STAT to EventFact.Outcome(TrainingStat.STRENGTH.wireName),
            FactRole.STAT_CHANGE to EventFact.Change(1), FactRole.FATIGUE_CHANGE to EventFact.Change(0))
        val wire = """{"COMMAND":"action.selfTrain","TRAINING_STAT":"strength","STAT_CHANGE":1,"FATIGUE_CHANGE":0}"""
        repeat(3) {
            assertEquals(wire, EventPayloadCodec.encodeFacts(facts))
            assertEquals(wire, EventPayloadCodec.encodeFacts(EventPayloadCodec.decodeFacts(wire)))
        }
    }
}
