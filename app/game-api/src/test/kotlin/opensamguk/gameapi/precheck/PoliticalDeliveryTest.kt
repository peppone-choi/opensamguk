package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticForbidden
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.PoliticalAdmission
import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticNation
import opensamguk.logic.domestic.DomesticPerson
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.LordStatus
import opensamguk.logic.input.Phase
import opensamguk.logic.input.PoliticalFailure
import opensamguk.logic.input.PoliticalInput
import opensamguk.logic.input.RuleProfile
import org.mockito.Mockito.`when`
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoliticalDeliveryTest {
    private val actor = DomesticPerson(7, "subject", 1, true, 0, 3, 60, 60, 60, 60, 60,
        "province-a", false, mapOf(LordStatus.META_KEY to false))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1), listOf(actor), emptyList(),
        listOf(DomesticCounty(11, "縣", 1, "province-a", "郡", emptyMap())),
        listOf(DomesticNation(1, "N1", 11, emptyMap())), setOf("province-a"))
    private val reader = mock(DomesticReader::class.java)

    private fun catalog(state: String): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.resign"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        assertTrue(row.containsMatchIn(original))
        return InputCatalog.parse(row.replace(original, "${'$'}1$state${'$'}3"))
    }

    @Test fun `subject sees an available no-target resignation and admission stores empty canonical arguments`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state))
        val options = PoliticalOptionsService(reader, catalog("HANDLER_READY")).options(actor.id, 42L)
            .single { it.inputId == PoliticalInput.RESIGN }
        assertTrue(options.available)
        assertTrue(options.targets.isEmpty())
        val admission = PoliticalAdmission(reader, catalog("HANDLER_READY"))
        assertEquals("{}", admission.canonicalArguments(PoliticalInput.RESIGN, actor.id, 42, 0, "{}"))
        assertEquals(PoliticalFailure.INVALID_INPUT.name, assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(PoliticalInput.RESIGN, actor.id, 42, 0, "{\"targetGeneralId\":8}")
        }.code)
        assertEquals("INVALID_TURN_SLOT", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(PoliticalInput.RESIGN, actor.id, 42, 12, "{}")
        }.code)
    }

    @Test fun `former subject and lord are unavailable in options and denied at admission`() {
        val admission = PoliticalAdmission(reader, catalog("HANDLER_READY"))
        for ((changed, reason) in listOf(
            actor.copy(nationId = 0) to PoliticalFailure.NOT_A_SUBJECT,
            actor.copy(officerLevel = 12, meta = mapOf(LordStatus.META_KEY to true)) to PoliticalFailure.ALREADY_LORD,
        )) {
            `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state.copy(people = listOf(changed))))
            val option = PoliticalOptionsService(reader, catalog("HANDLER_READY")).options(actor.id, 42L)
                .single { it.inputId == PoliticalInput.RESIGN }
            assertFalse(option.available)
            assertEquals(reason.name, option.code)
            assertEquals(reason.name, assertFailsWith<AdmissionDenied> {
                admission.canonicalArguments(PoliticalInput.RESIGN, actor.id, 42, 0, "{}")
            }.code)
        }
    }

    @Test fun `catalog gate and owner guard deny before reservation`() {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = state))
        val planned = PoliticalAdmission(reader, catalog("PLANNED"))
        assertEquals("NOT_DELIVERED", assertFailsWith<AdmissionDenied> {
            planned.canonicalArguments(PoliticalInput.RESIGN, actor.id, 42, 0, "{}")
        }.code)
        assertEquals("UNAUTHORIZED", assertFailsWith<AdmissionDenied> {
            PoliticalAdmission(reader, catalog("HANDLER_READY"))
                .canonicalArguments(PoliticalInput.RESIGN, actor.id, null, 0, "{}")
        }.code)
        doThrow(DomesticForbidden()).`when`(reader).requireOwner(actor.id, 99L)
        assertEquals("FORBIDDEN", assertFailsWith<AdmissionDenied> {
            PoliticalAdmission(reader, catalog("HANDLER_READY"))
                .canonicalArguments(PoliticalInput.RESIGN, actor.id, 99, 0, "{}")
        }.code)
    }
}
