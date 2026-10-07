package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.FieldAdmission
import opensamguk.logic.domestic.*
import opensamguk.logic.input.CaptiveState
import opensamguk.logic.input.Phase
import opensamguk.logic.input.RuleProfile

class FieldOptionsServiceTest {
    private val person = DomesticPerson(7, "장수", 2, true, 0, 1, 50, 50, 50, 50, 50,
        "province-a", false, emptyMap())
    private val county = DomesticCounty(11, "현", 2, "province-a", "군", emptyMap())
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(person), emptyList(), listOf(county), emptyList(), setOf("province-a"))
    private val levels = CountyLevels(50_000, 100_000, 100, 1000, 100, 1000, 100, 1000, 50.0,
        100, 1000, 100, 1000)
    private val reader = mock(DomesticReader::class.java)
    private val service = FieldOptionsService(reader)

    private fun snapshot(projection: DomesticProjection) {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection,
            countyLevels = mapOf(county.id to levels)))
    }

    @Test fun `captive field options show the admission code with a concrete reason`() {
        val captive = CaptiveState(8, "province-a", state.now, "encounter-1").toMetaValue()
        for (marker in listOf(captive, mapOf("captorGeneralId" to 8), null)) {
            snapshot(state.copy(people = listOf(person.copy(meta = mapOf(CaptiveState.META_KEY to marker)))))
            val option = service.options(FieldInput.FARM, person.id, 41)
            assertFalse(option.available)
            assertEquals("STATE_UNAVAILABLE", option.code)
            assertEquals(FieldRules.CAPTIVE_REASON, option.reason)
            assertEquals("STATE_UNAVAILABLE", assertFailsWith<AdmissionDenied> {
                FieldAdmission(reader).canonicalArguments(FieldInput.FARM, person.id, 41, 0, "{}")
            }.code)
        }
    }

    @Test fun `free human and NPC field options remain available`() {
        for (actor in listOf(person, person.copy(userOwned = false, npcState = 2))) {
            snapshot(state.copy(people = listOf(actor)))
            val option = service.options(FieldInput.FARM, actor.id, 41)
            assertTrue(option.available)
            assertNull(option.code)
            assertEquals(county.id, option.countyId)
        }
    }
}
