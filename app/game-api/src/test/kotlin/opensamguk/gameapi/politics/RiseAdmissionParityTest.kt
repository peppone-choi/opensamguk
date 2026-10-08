package opensamguk.gameapi.politics

import kotlin.test.*
import opensamguk.gameapi.precheck.PoliticalOptionsService
import opensamguk.gameapi.read.*
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.PoliticalAdmission
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*
import org.mockito.Mockito.*

/** Delivered-catalog injection exercises structural parity without approving the proposed policy. */
class RiseAdmissionParityTest {
    private val reader = mock(DomesticReader::class.java)
    private val actor = DomesticPerson(1, "본인", 0, true, 0, 0, 60, 60, 60, 60, 60,
        "county-province", false, mapOf(LordStatus.META_KEY to false,
            PersonPolicyState.META_KEY to PersonPolicyState(PoliticalDesign.CANON.riseMinimumRenown,
                true, "synthetic-rise-structure", "v1", 1).toMetaValue()), troopId = 0, spatialStateAvailable = true)
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), listOf(actor),
        emptyList(), listOf(DomesticCounty(10, "빈 현", 0, "county-province", "郡", emptyMap())),
        emptyList(), setOf("county-province"), troops = emptyList())

    private val delivered by lazy {
        val source = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.rise"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        assertTrue(row.containsMatchIn(source))
        InputCatalog.parse(row.replace(source, "${'$'}1HANDLER_READY${'$'}3"))
    }

    private fun options() = PoliticalOptionsService(reader, delivered).options(1, 42L)
        .single { it.inputId == PoliticalInput.RISE }
    private fun admission(raw: String? = "{}", slot: Int = 0, owner: Int? = 42) =
        PoliticalAdmission(reader, delivered).canonicalArguments(PoliticalInput.RISE, 1, owner, slot, raw)

    @Test fun `the same structural snapshot permits only the empty canonical arguments`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state))
        assertTrue(options().available)
        assertTrue(options().targets.isEmpty())
        assertEquals("{}", admission(" { } "))
        verify(reader, times(3)).requireOwner(1, 42L)
    }

    @Test fun `options and admission agree on corrupt or unavailable military and retinue state`() {
        val child = actor.copy(id = 2, name = "휘하")
        val cases = listOf(
            state.copy(troops = null),
            state.copy(people = listOf(actor.copy(troopId = 9))),
            state.copy(people = listOf(actor.copy(troopId = 1), child.copy(troopId = 1)),
                troops = listOf(DomesticTroop(1, 0))),
            state.copy(people = listOf(actor, child.copy(nationId = 9)),
                cards = listOf(DomesticCard(20, 1, 2, "guest"))),
        )
        for (snapshot in cases) {
            `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = snapshot))
            val option = options()
            assertFalse(option.available)
            assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, option.code)
            assertEquals(option.code, assertFailsWith<AdmissionDenied> { admission() }.code)
        }
    }

    @Test fun `unknown current snapshot fails closed at both read and admission`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(failure = "UNAVAILABLE"))
        assertFalse(options().available)
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name, options().code)
        assertEquals(PoliticalFailure.STATE_UNAVAILABLE.name,
            assertFailsWith<AdmissionDenied> { admission() }.code)
    }

    @Test fun `actor target cost and duplicate raw fields are rejected without reading world state`() {
        for (raw in listOf(null, "null", "[]", "{\"actorId\":1}", "{\"targetGeneralId\":2}",
            "{\"gold\":0}", "{\"actorId\":1,\"actorId\":1}", "{\"renown\":50}")) {
            assertEquals(PoliticalFailure.INVALID_INPUT.name,
                assertFailsWith<AdmissionDenied> { admission(raw) }.code)
        }
        verify(reader, never()).snapshot()
    }

    @Test fun `unowned actor and out of range slots fail before reading mutable state`() {
        assertEquals("UNAUTHORIZED", assertFailsWith<AdmissionDenied> { admission(owner = null) }.code)
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(1, 99L)
        assertEquals("FORBIDDEN", assertFailsWith<AdmissionDenied> { admission(owner = 99) }.code)
        assertEquals("INVALID_TURN_SLOT", assertFailsWith<AdmissionDenied> { admission(slot = -1) }.code)
        assertEquals("INVALID_TURN_SLOT", assertFailsWith<AdmissionDenied> { admission(slot = 12) }.code)
        verify(reader, never()).snapshot()
    }

    @Test fun `the production catalog keeps rise unavailable and denies admission while policy is pending`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state))
        val option = PoliticalOptionsService(reader).options(1, 42L)
            .single { it.inputId == PoliticalInput.RISE }
        assertFalse(option.available)
        assertEquals(InputRejection.NOT_DELIVERED.name, option.code)
        assertTrue(option.targets.isEmpty())
        assertEquals(InputRejection.NOT_DELIVERED.name, assertFailsWith<AdmissionDenied> {
            PoliticalAdmission(reader).canonicalArguments(PoliticalInput.RISE, 1, 42, 0, "{}")
        }.code)
    }
}
