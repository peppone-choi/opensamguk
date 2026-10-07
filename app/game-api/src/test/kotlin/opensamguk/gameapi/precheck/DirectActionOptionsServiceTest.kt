package opensamguk.gameapi.precheck

import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.DomesticReader
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.reserve.AdmissionDenied
import opensamguk.gameapi.reserve.DirectActionAdmission
import opensamguk.logic.content.ItemCatalogJson
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*

class DirectActionOptionsServiceTest {
    private val actor = DomesticPerson(7, "장수", 1, true, 0, 1, 60, 60, 60, 60, 60,
        "province-a", false, emptyMap(), gold = 500)
    private val county = DomesticCounty(11, "현", 1, "province-a", "군", mapOf(
        CountyWarehouse.META_KEY to CountyWarehouse(11, 0, Resources(money = 500, grain = 500)).toMetaValue()))
    private val state = DomesticProjection(RuleProfile.HWIHA, Phase(200, 1, 1),
        listOf(actor), emptyList(), listOf(county), emptyList(), setOf("province-a"))
    private val reader = mock(DomesticReader::class.java)
    private val service = DirectActionOptionsService(reader)

    private fun snapshot(projection: DomesticProjection) {
        `when`(reader.snapshot()).thenReturn(DomesticSnapshot(state = projection))
    }

    @Test fun `captive direct options deny all inputs with admission code and concrete reason`() {
        val captive = CaptiveState(8, "province-a", state.now, "encounter-1").toMetaValue()
        for (marker in listOf(captive, mapOf("version" to 1), null)) {
            snapshot(state.copy(people = listOf(actor.copy(meta = mapOf(CaptiveState.META_KEY to marker)))))
            for (inputId in DirectInput.INPUT_IDS) {
                val option = service.options(actor.id, 41, inputId)
                assertFalse(option.available)
                assertEquals("STATE_UNAVAILABLE", option.code)
                assertEquals(DirectRules.CAPTIVE_REASON, option.reason)
                assertTrue(option.choices.isEmpty())
                if (inputId == DirectInput.EQUIPMENT) assertEquals(
                    ItemCatalogJson.CANON.equipment.associate { it.id to it.name }, option.equipmentNames)
            }
            assertEquals("STATE_UNAVAILABLE", assertFailsWith<AdmissionDenied> {
                DirectActionAdmission(reader).canonicalArguments(DirectInput.GRAIN, actor.id, 41, 0,
                    """{"side":"BUY","amount":1}""")
            }.code)
        }
    }

    @Test fun `free human and NPC retain grain availability`() {
        for (person in listOf(actor, actor.copy(userOwned = false, npcState = 2))) {
            snapshot(state.copy(people = listOf(person)))
            val option = service.options(person.id, 41, DirectInput.GRAIN)
            assertTrue(option.available)
            assertTrue(option.choices.any { it.available })
        }
    }
}
