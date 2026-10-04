package opensamguk.gameapi.read

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.web.ImperialPresenceController
import opensamguk.logic.imperial.ImperialHouse
import opensamguk.logic.imperial.ImperialLineStatus
import opensamguk.logic.imperial.ImperialWorldCodec
import opensamguk.logic.imperial.ImperialWorldState
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.ProvinceControlSnapshot
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.HttpStatus
import java.nio.file.Path
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ImperialPresenceReaderTest {
    private val bundle = WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1133)
    private val topology = bundle.projection.topology
    private val home = assertNotNull(bundle.projection.bindingsByCityId.getValue(12).landProvinceId)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val controller = ImperialPresenceController(ImperialPresenceReader(worlds, generals, artifacts, spatial))
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

    private fun readyPosition(node: StrategicNodeRef? = StrategicNodeRef.LandProvince(home),
                              courtCityId: Int? = 11) {
        val world = seeded(courtCityId)
        `when`(worlds.findProcessWorld()).thenReturn(world)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 1, name = "황제", cityId = 12)
        ))
        val states = node?.let { listOf(GeneralPositionState(topology.topologyRevision,
            topology.contentHash, 101, it, 1)) } ?: emptyList()
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology),
            GeneralPositionSnapshot.fromTopology(topology, states)))
    }

    @Test
    fun `ready response uses spatial position and matches the public fixture`() {
        readyPosition()
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(fixture("ready"), mapper.valueToTree<JsonNode>(response.body))
        verify(generals, times(1)).findById(101)
    }

    @Test
    fun `public emperor name comes from the current general row`() {
        readyPosition()
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 1, name = "이름이 바뀐 황제", cityId = 12)
        ))
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        val badge = mapper.valueToTree<JsonNode>(response.body).path("badges").get(0)
        assertEquals("이름이 바뀐 황제", badge.path("emperorName").asText())
        assertEquals(101, badge.path("emperorGeneralId").asInt())
        verify(generals, times(1)).findById(101)
    }

    @Test
    fun `blank emperor name is explicit null without changing presence status`() {
        readyPosition()
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 1, name = " \t", cityId = 12)
        ))
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("READY", response.body!!.status)
        val badge = mapper.valueToTree<JsonNode>(response.body).path("badges").get(0)
        assertEquals(true, badge.has("emperorName"))
        assertEquals(true, badge.path("emperorName").isNull)
    }

    @Test
    fun `emperor from another world cannot expose a name or a presence badge`() {
        readyPosition()
        `when`(generals.findById(101)).thenReturn(Optional.of(
            GeneralReadEntity(id = 101, worldId = 2, name = "다른 월드 황제", cityId = 12)
        ))
        val response = controller.presence()
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(fixture("unavailable"), mapper.valueToTree<JsonNode>(response.body))
    }

    @Test
    fun `missing seed is explicit and does not read generals`() {
        `when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(id = 1))
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(fixture("not-seeded"), mapper.valueToTree<JsonNode>(response.body))
        verifyNoInteractions(generals, artifacts, spatial)
    }

    @Test
    fun `missing emperor general is unavailable rather than the court city`() {
        readyPosition()
        `when`(generals.findById(101)).thenReturn(Optional.empty())
        val response = controller.presence()
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(fixture("unavailable"), mapper.valueToTree<JsonNode>(response.body))
    }

    @Test
    fun `missing spatial position is unavailable even with a reference city`() {
        readyPosition(node = null)
        val response = controller.presence()
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(fixture("unavailable"), mapper.valueToTree<JsonNode>(response.body))
    }

    @Test
    fun `emperor in a cityless province is placed there instead of the last city`() {
        val cityProvinces = bundle.projection.bindingsByCityId.values.mapNotNull { it.landProvinceId }.toSet()
        val cityless = topology.landProvinceIds.first { it !in cityProvinces }
        readyPosition(StrategicNodeRef.LandProvince(cityless))
        val response = controller.presence()
        assertEquals(HttpStatus.OK, response.statusCode)
        val badge = mapper.valueToTree<JsonNode>(response.body).path("badges").get(0)
        assertEquals("LAND_PROVINCE", badge.path("emperorNodeKind").asText())
        assertEquals(cityless, badge.path("emperorNodeId").asText())
        assertNull(response.body!!.badges.single().emperorCityId)
    }

    @Test
    fun `court city missing from the pinned artifact makes presence unavailable`() {
        readyPosition(courtCityId = Int.MAX_VALUE)
        val response = controller.presence()
        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        assertEquals(fixture("unavailable"), mapper.valueToTree<JsonNode>(response.body))
    }

    @Test
    fun `unknown court city is serialized as explicit null`() {
        readyPosition(courtCityId = null)
        val json = mapper.valueToTree<JsonNode>(controller.presence().body)
        assertEquals(true, json.path("badges").get(0).has("courtCityId"))
        assertEquals(true, json.path("badges").get(0).path("courtCityId").isNull)
    }
}
