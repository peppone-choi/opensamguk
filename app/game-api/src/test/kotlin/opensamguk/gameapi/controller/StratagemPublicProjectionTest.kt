package opensamguk.gameapi.controller

import kotlin.test.Test
import kotlin.test.assertFalse
import opensamguk.gameapi.read.*
import opensamguk.logic.input.Phase
import opensamguk.logic.input.StratagemHand
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class StratagemPublicProjectionTest {
    @Test fun `anonymous general list never serializes stored stratagem hand`() {
        val generals=mock(GeneralReadRepository::class.java)
        val nations=mock(NationReadRepository::class.java)
        val cities=mock(CityReadRepository::class.java)
        val general=GeneralReadEntity().apply {
            id=1;name="actor"
            meta=mapOf(StratagemHand.META_KEY to StratagemHand.initial(1,Phase(200,1,1)).toMetaValue())
        }
        `when`(generals.findAll()).thenReturn(listOf(general))
        `when`(nations.findAll()).thenReturn(emptyList())
        `when`(cities.findAll()).thenReturn(emptyList())
        val mvc=MockMvcBuilders.standaloneSetup(GeneralsController(generals,nations,cities)).build()
        val json=mvc.perform(get("/api/generals")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].generalId").value(1)).andExpect(jsonPath("$[0].name").value("actor")).andReturn().response.contentAsString
        assertFalse(json.contains(StratagemHand.META_KEY))
        assertFalse(json.contains("drawPile"));assertFalse(json.contains("lastDrawPhase"))
    }
}
