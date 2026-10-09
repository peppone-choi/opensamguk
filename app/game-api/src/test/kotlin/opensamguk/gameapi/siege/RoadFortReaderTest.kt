package opensamguk.gameapi.siege

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.RoadFortDto
import opensamguk.gameapi.dto.RoadGateDto
import opensamguk.gameapi.dto.RoadFortsResponse
import opensamguk.gameapi.read.*
import opensamguk.gameapi.web.RoadFortController
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.seed.CountyGeographyJson
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.RoadFort
import opensamguk.logic.input.RoadFortSiegeInput
import opensamguk.logic.input.RoadFortState
import opensamguk.logic.world.*
import org.mockito.Mockito.*
import java.security.MessageDigest
import java.util.Optional
import kotlin.test.*

class RoadFortReaderTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worlds = mock(WorldStateReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val mapper = ObjectMapper()
    private val reader = RoadFortReader(generals, worlds, artifacts, spatial, gameKv, diplomacy, mapper)
    private val controller = RoadFortController(reader)
    private val world = WorldStateReadEntity(id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))
    private val actor = GeneralReadEntity(id = 1, worldId = 1, nationId = 1, userId = "41")
    private val bundle = mock(ResolvedWorldArtifacts::class.java)
    private val tiles = """{"provinceRecords":[
        {"id":"A","displayName":"가 구역"},{"id":"B","displayName":"나 구역"},
        {"id":"C","displayName":"다 구역"},{"id":"D","displayName":"라 구역"}]}""".toByteArray()
    private lateinit var topology: StrategicTopologySnapshot

    private fun edge(id: String, from: String, to: String) = TraversalEdge(id,
        StrategicNodeRef.LandProvince(from), StrategicNodeRef.LandProvince(to), TraversalMode.LAND,
        false, 1, 100, RiskBand.LOW, SeasonalAvailability.ALWAYS,
        sourceRefs = listOf("synthetic-qa"), confidence = EvidenceConfidence.EXACT)

    private fun fort(edge: String, province: String, owner: Int, row: Int = 2) =
        RoadFort(RoadFort.siteId(edge, row, 3), edge, province, row, 3, owner, 73, 140)

    private fun setup(source: ByteArray = tiles, pinnedHash: String? = null) {
        val hash = pinnedHash ?: MessageDigest.getInstance("SHA-256").digest(source)
            .joinToString("") { "%02x".format(it) }
        topology = StrategicTopologySnapshot("synthetic-road-forts", setOf("A", "B", "C", "D"),
            emptyList(), listOf(edge("near", "A", "B"), edge("far", "C", "D")), emptyList(),
            mapOf(CountyGeographyJson.TILES to hash))
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(generals.findById(99)).thenReturn(Optional.empty())
        `when`(worlds.findProcessWorld()).thenReturn(world)
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, listOf(
            CityReadEntity(id = 77, worldId = 1, name = "인근 도시를 대신 쓰지 않는다", nationId = 1)), bundle))
        `when`(bundle.variant).thenReturn(WorldMapVariant.V3_1428)
        `when`(bundle.projection).thenReturn(StrategicRouteProjection(topology, emptyList()))
        `when`(bundle.artifactBytes(CountyGeographyJson.RUNTIME_MAP)).thenReturn("""{"cities":[]}""".toByteArray())
        `when`(bundle.artifactBytes(CountyGeographyJson.TILES)).thenReturn(source)
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(GeneralPositionState(topology.topologyRevision, topology.contentHash, 1,
                    StrategicNodeRef.LandProvince("A"), 1)))))
        `when`(diplomacy.findAll()).thenReturn(listOf(DiplomacyReadEntity(worldId = 1,
            srcNationId = 1, destNationId = 2, stateCode = 0)))
    }

    private fun stored(forts: List<RoadFort>) {
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", RoadFortState.META_KEY))
            .thenReturn(GameKvEntity("game_env", "game_env", RoadFortState.META_KEY,
                mapper.writeValueAsString(RoadFortState.toMetaValue(forts)), 1))
    }

    private fun expected(fort: RoadFort, canBesiege: Boolean, name: String?) = RoadFortDto(
        fort.id, fort.edgeId, fort.provinceId, fort.row, fort.col, fort.ownerNationId,
        fort.wall, fort.garrison, fort.besiegerGeneralId, fort.siegeProgress, canBesiege, name)

    @Test fun `names decorate only owned nearby or personally besieged forts without changing their state`() {
        setup()
        val own = fort("far", "D", 1)
        val near = fort("near", "B", 2)
        val besieged = fort("far", "C", 2, 4).copy(
            besiegerNationId = 1, besiegerGeneralId = 1, siegeProgress = 37)
        val hidden = fort("far", "C", 2, 5)
        val orphan = fort("missing-edge", "A", 1)
        stored(listOf(own, near, besieged, hidden, orphan))
        val response = reader.forts(1, 41)
        assertEquals(RoadFortsResponse("READY", listOf(expected(own, false, "라 구역"),
            expected(besieged, false, "다 구역"), expected(near, true, "나 구역"))), response)
        assertEquals(near.id, RoadFortSiegeInput.parse(mapper.writeValueAsString(mapOf("fortId" to response.forts.last().id))))
    }

    @Test fun `unmapped province stays null without falling back to edge endpoints or a nearby city`() {
        setup()
        val unmapped = fort("near", "unmapped", 2)
        stored(listOf(unmapped))
        assertEquals(RoadFortsResponse("READY", listOf(expected(unmapped, true, null))), reader.forts(1, 41))
    }

    @Test fun `missing display name keeps the fort and READY status with a null name`() {
        setup(source = tiles.toString(Charsets.UTF_8).replace("\"displayName\":\"나 구역\"", "\"other\":\"나 구역\"").toByteArray())
        val near = fort("near", "B", 2)
        stored(listOf(near))
        assertEquals(RoadFortsResponse("READY", listOf(expected(near, true, null))), reader.forts(1, 41))
    }

    @Test fun `unverified name source keeps visibility state and siege permission unchanged`() {
        setup(pinnedHash = "a".repeat(64))
        val own = fort("far", "D", 1)
        val near = fort("near", "B", 2)
        val hidden = fort("far", "C", 2, 5)
        stored(listOf(own, near, hidden))
        assertEquals(RoadFortsResponse("READY", listOf(expected(own, false, null), expected(near, true, null))), reader.forts(1, 41))
    }

    @Test fun `all hidden forts still produce an empty READY response`() {
        setup()
        stored(listOf(fort("far", "C", 2)))
        assertEquals(RoadFortsResponse("READY"), reader.forts(1, 41))
    }

    @Test fun `name lookup failure leaves road gate projection and road mode intact`() {
        setup(pinnedHash = "a".repeat(64))
        val cell = StrategicFortCell("B", 2, 3)
        val gate = StrategicRoadGate("near", 0, 0, 2, 3, 1, initiallyBuilt = true,
            historicalRouteIds = listOf("synthetic-route"), fortCells = listOf(cell))
        `when`(bundle.projection).thenReturn(StrategicRouteProjection(topology,
            listOf(StrategicRouteBinding(77, "county77", "place77", "A", true)),
            presentation = StrategicMapPresentation(4, 4, "a".repeat(64), emptyList(), emptyMap(), listOf(gate))))
        val near = fort("near", "B", 2)
        stored(listOf(near))
        assertEquals(RoadFortsResponse("READY", listOf(expected(near, true, null)),
            listOf(RoadGateDto("near", "A", "B", true, true, listOf("synthetic-route"), listOf(cell))), true),
            reader.forts(1, 41))
    }

    @Test fun `ownership is checked before world state and name access`() {
        setup()
        assertFailsWith<CampForbidden> { reader.forts(1, 42) }
        assertFailsWith<CampForbidden> { reader.forts(99, 41) }
        assertFailsWith<CampForbidden> { reader.forts(1, 0) }
        assertFailsWith<CampForbidden> { reader.forts(1, Int.MAX_VALUE.toLong() + 1) }
        verifyNoInteractions(worlds, artifacts, spatial, gameKv, diplomacy)
    }

    @Test fun `HTTP ownership no-store and soft world statuses retain their contract`() {
        setup()
        stored(listOf(fort("near", "B", 2)))
        listOf(null, 0L, Int.MAX_VALUE.toLong() + 1).forEach {
            assertEquals(401, controller.forts(it, 1).statusCode.value())
        }
        assertEquals(403, controller.forts(42, 1).statusCode.value())
        val ok = controller.forts(41, 1)
        assertEquals(200, ok.statusCode.value()); assertEquals("no-store", ok.headers.cacheControl)
        world.config = mapOf("worldFormat" to "SAMMO")
        assertEquals(RoadFortsResponse("UNSUPPORTED_WORLD_FORMAT"), reader.forts(1, 41))
        world.config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")
        `when`(worlds.findProcessWorld()).thenReturn(null)
        assertEquals(RoadFortsResponse("UNAVAILABLE"), reader.forts(1, 41))
    }

    @Test fun `unavailable artifacts or position retain empty soft responses`() {
        setup()
        `when`(artifacts.resolve()).thenReturn(null)
        assertEquals(RoadFortsResponse("UNAVAILABLE"), reader.forts(1, 41))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), null))
        assertEquals(RoadFortsResponse("UNAVAILABLE"), reader.forts(1, 41))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, emptyList(), bundle))
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology)))
        assertEquals(RoadFortsResponse("UNAVAILABLE"), reader.forts(1, 41))
    }

    @Test fun `invalid stored fort data stays UNAVAILABLE`() {
        setup()
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", RoadFortState.META_KEY))
            .thenReturn(GameKvEntity("game_env", "game_env", RoadFortState.META_KEY, "{}", 1))
        assertEquals(RoadFortsResponse("UNAVAILABLE"), reader.forts(1, 41))
    }

    @Test fun `wire contract adds a nullable provinceName and retains the canonical fort id`() {
        val boot = org.springframework.http.converter.json.Jackson2ObjectMapperBuilder.json().build<ObjectMapper>()
        val fort = fort("near", "B", 2)
        for (name in listOf("나 구역", null)) {
            val json = boot.readTree(boot.writeValueAsBytes(expected(fort, true, name)))
            assertEquals(setOf("id", "edgeId", "provinceId", "row", "col", "ownerNationId", "wall",
                "garrison", "besiegerGeneralId", "siegeProgress", "canBesiege", "provinceName"),
                json.fieldNames().asSequence().toSet())
            assertEquals(fort.id, json["id"].asText())
            if (name == null) assertTrue(json["provinceName"].isNull) else assertEquals(name, json["provinceName"].asText())
            assertEquals(fort.id, RoadFortSiegeInput.parse(boot.writeValueAsString(mapOf("fortId" to json["id"].asText()))))
        }
    }
}
