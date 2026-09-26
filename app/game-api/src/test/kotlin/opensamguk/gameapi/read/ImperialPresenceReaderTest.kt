package opensamguk.gameapi.read

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.web.ImperialPresenceController
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialWorldCodec
import opensamguk.logic.imperial.ImperialWorldState
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.util.Optional
import kotlin.test.assertEquals

class ImperialPresenceReaderTest {
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val controller = ImperialPresenceController(ImperialPresenceReader(worlds, generals))
    private val mapper = ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private fun seeded(courtCityId: Int? = 11): WorldStateReadEntity {
        val state = ImperialWorldState(
            houses = listOf(ImperialHouse("test_line", "시험 계통", ImperialLineStatus.ACTIVE,
                101, null, emptyList(), null, null, courtCityId, 50)),
            allegiances = emptyList(),
            transitions = emptyList(),
        )
        return WorldStateReadEntity(id = 1, meta = mapOf(ImperialWorldCodec.META_KEY to ImperialWorldCodec.write(state)))
    }

    private fun fixture(name: String) = mapper.readTree(
        requireNotNull(javaClass.getResourceAsStream("/imperial/presence-$name.json"))
    )

    @Test
    fun `ready response uses the general city and matches the public fixture`() {
        `when`(worlds.findProcessWorld()).thenReturn(seeded())
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 1, name = "황제", cityId = 12)
        ))
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(fixture("ready"), mapper.valueToTree<JsonNode>(response.body))
    }

    @Test
    fun `missing seed is explicit and does not read general positions`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1))
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(fixture("not-seeded"), mapper.valueToTree<JsonNode>(response.body))
        verifyNoInteractions(generals)
    }

    @Test
    fun `missing emperor position is unavailable rather than the court city`() {
        `when`(worlds.findProcessWorld()).thenReturn(seeded())
        `when`(generals.findById(101)).thenReturn(Optional.empty())
        val response = controller.presence()
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(fixture("unavailable"), mapper.valueToTree<JsonNode>(response.body))
    }

    @Test
    fun `unknown court city is serialized as explicit null`() {
        `when`(worlds.findProcessWorld()).thenReturn(seeded(courtCityId = null))
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 1, name = "황제", cityId = 12)
        ))
        val json = mapper.valueToTree<JsonNode>(controller.presence().body)
        assertEquals(true, json.path("badges").get(0).has("courtCityId"))
        assertEquals(true, json.path("badges").get(0).path("courtCityId").isNull)
    }
}
