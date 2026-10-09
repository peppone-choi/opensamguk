package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.RetireAdmission
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*

class RetireEligibilityTest {
    private val actor = DomesticPerson(7, "주인", 1, true, 0, 12, 60, 60, 60, 60, 60,
        "province", false, mapOf(LordStatus.META_KEY to true), age = 60)
    private val heir = actor.copy(id = 8, name = "후계", userOwned = false, npcState = 2,
        officerLevel = 1, meta = policy(8, 30), age = 30)
    private fun policy(id: Int, capacity: Int) = mapOf(LordStatus.META_KEY to false,
        PersonPolicyState.META_KEY to PersonPolicyState(capacity, false, "synthetic-test", "1", id).toMetaValue())
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(actor, heir), listOf(DomesticCard(10, actor.id, heir.id, "guest", heir.name)),
        emptyList(), listOf(DomesticNation(1, "국", null, emptyMap(), chiefGeneralId = actor.id)), setOf("province"))
    private val reader = mock(DomesticReader::class.java)
    private val args = """{"successorGeneralId":8}"""

    // Isolate the shared eligibility contract without activating the production catalog.
    private fun deliveredCatalog(): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.retire"[\s\S]*?"deliveryState":\s*")PLANNED(")""")
        assertTrue(row.containsMatchIn(original))
        return InputCatalog.parse(row.replace(original, "${'$'}1HANDLER_READY${'$'}2"))
    }

    private fun assertDenied(snapshot: DomesticProjection, failure: RetireFailure) {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = snapshot))
        val options = RetireOptionsService(reader, deliveredCatalog()).options(actor.id, 42L)
        assertFalse(options.available)
        assertEquals(failure.name, options.code)
        assertEquals(failure.message, options.reason)
        assertEquals(failure.name, assertFailsWith<AdmissionDenied> {
            RetireAdmission(reader, deliveredCatalog()).canonicalArguments(actor.id, 42, 0, args)
        }.code)
        assertEquals(snapshot, reader.snapshot().state)
    }

    @Test fun `options and admission share the age boundary and reject missing authoritative age`() {
        for (age in listOf(0, 20, 59))
            assertDenied(state.copy(people = listOf(actor.copy(age = age), heir)), RetireFailure.AGE_TOO_YOUNG)
        for (age in listOf(null, -1))
            assertDenied(state.copy(people = listOf(actor.copy(age = age), heir)), RetireFailure.STATE_UNAVAILABLE)
        for (age in listOf(60, 61)) {
            `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state =
                state.copy(people = listOf(actor.copy(age = age), heir))))
            assertTrue(RetireOptionsService(reader, deliveredCatalog()).options(actor.id, 42L).available)
            assertEquals(args, RetireAdmission(reader, deliveredCatalog()).canonicalArguments(actor.id, 42, 0, args))
        }
    }

    @Test fun `options and admission reject cross nation human retired and non NPC heirs`() {
        val invalid = listOf(heir.copy(nationId = 2), heir.copy(userOwned = true),
            heir.copy(meta = mapOf("retired" to true))) +
            listOf(-1, 0, 1, 3, 5, 6, 9, 99).map { heir.copy(npcState = it) }
        for (person in invalid) assertDenied(state.copy(people = listOf(actor, person)),
            RetireFailure.SUCCESSOR_UNAVAILABLE_FOR_CONTROL)
        assertDenied(state.copy(cards = emptyList()), RetireFailure.SUCCESSOR_NOT_RETAINER)
        assertDenied(state.copy(cards = listOf(state.cards.single().copy(masterId = 9))),
            RetireFailure.SUCCESSOR_NOT_RETAINER)
    }

    @Test fun `bound ruler mismatch is rejected by both call sites`() {
        assertDenied(state.copy(nations = listOf(state.nations.single().copy(chiefGeneralId = heir.id))),
            RetireFailure.STATE_UNAVAILABLE)
    }

    @Test fun `options and reservation admission use the unchanged successor capacity`() {
        val follower = heir.copy(id = 9, name = "휘하", meta = policy(9, 0))
        for (capacity in listOf(0, 5, 6, 7)) {
            val snapshot = state.copy(people = listOf(actor, heir.copy(meta = policy(8, capacity)), follower),
                cards = state.cards + DomesticCard(11, actor.id, follower.id, "staff", follower.name))
            if (capacity < 6) assertDenied(snapshot, RetireFailure.SUCCESSOR_RENOWN_EXCEEDED)
            else {
                `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = snapshot))
                assertTrue(RetireOptionsService(reader, deliveredCatalog()).options(actor.id, 42L).available)
                assertEquals(args, RetireAdmission(reader, deliveredCatalog()).canonicalArguments(actor.id, 42, 0, args))
            }
            assertEquals(capacity, PersonPolicyState.read(snapshot.person(8)!!.meta)!!.renownCapacity)
        }
        assertDenied(state.copy(people = listOf(actor, heir.copy(meta = emptyMap()))), RetireFailure.STATE_UNAVAILABLE)
    }

    @Test fun `wrong owner is rejected before reading succession state`() {
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(actor.id, 43L)
        assertFailsWith<DomesticForbidden> { RetireOptionsService(reader).options(actor.id, 43L) }
        assertEquals("FORBIDDEN", assertFailsWith<AdmissionDenied> {
            RetireAdmission(reader).canonicalArguments(actor.id, 43, 0, args)
        }.code)
        assertEquals("UNAUTHORIZED", assertFailsWith<AdmissionDenied> {
            RetireAdmission(reader).canonicalArguments(actor.id, null, 0, args)
        }.code)
        verify(reader, never()).snapshot()
    }

    @Test fun `eligible retirement remains planned at both production call sites`() {
        assertEquals(InputDeliveryState.PLANNED, InputCatalog.load()[RetireInput.INPUT_ID]!!.deliveryState)
        val options = RetireOptionsService(reader).options(actor.id, 42L)
        assertFalse(options.available)
        assertEquals(InputRejection.NOT_DELIVERED.name, options.code)
        assertTrue(options.successors.isEmpty())
        assertEquals(InputRejection.NOT_DELIVERED.name, assertFailsWith<AdmissionDenied> {
            RetireAdmission(reader).canonicalArguments(actor.id, 42, 0, args)
        }.code)
        verify(reader, never()).snapshot()
    }
}
