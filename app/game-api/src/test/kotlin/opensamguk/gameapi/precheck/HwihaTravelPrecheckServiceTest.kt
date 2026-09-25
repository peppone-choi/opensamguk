package opensamguk.gameapi.precheck

import java.util.Optional
import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.gameapi.read.*
import opensamguk.gameapi.reserve.*
import opensamguk.gameapi.web.HwihaTravelOptionsController
import opensamguk.infra.seed.ResolvedHanWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.world.*

class HwihaTravelPrecheckServiceTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val service = HwihaTravelPrecheckService(generals, retainers, artifacts, spatial)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val pin = "a".repeat(64)
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW,
            SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin, listOf(LandMarchEdgeMetric("ab", 40, 40)))

    private fun setup(): GeneralReadEntity {
        val actor = GeneralReadEntity(id = 1, worldId = 1, name = "본인", nationId = 1, userId = "41")
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(generals.findAll()).thenReturn(listOf(actor))
        `when`(retainers.findAll()).thenReturn(emptyList())
        `when`(retainers.allBugoks()).thenReturn(emptyList())
        val bundle = mock(ResolvedHanWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(HanStrategicRouteProjection(topology, listOf(
            HanStrategicRouteBinding(1, "r1", "p1", "A"), HanStrategicRouteBinding(2, "r2", "p2", "B"))))
        `when`(bundle.landMarchMetrics).thenReturn(metrics)
        val world = WorldStateReadEntity(id = 1, config = mapOf("ruleProfile" to "HWIHA"), meta = mapOf(
            LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
            HwihaMarchReactions.META_KEY to HwihaMarchReactions.Empty.toMetaValue()))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = 1, worldId = 1), CityReadEntity(id = 2, worldId = 1)), bundle))
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(GeneralPositionState(topology.topologyRevision, topology.contentHash, 1, a, 1)))))
        return actor
    }

    @Test fun `owner check precedes map reads and options use the shared route assessment`() {
        setup()
        assertFailsWith<TravelReadForbidden> { service.options(1, HwihaTravelInput.MOVE, 42) }
        verifyNoInteractions(artifacts, spatial)
        val options = service.options(1, HwihaTravelInput.MOVE, 41)
        assertTrue(options.available)
        assertEquals(listOf("A", "B"), options.destinations.map { it.provinceId })
        assertEquals("ALREADY_THERE", options.destinations.first().code)
        assertTrue(options.destinations.last().available)
        assertIs<HwihaTravelAssessment.Eligible>(service.assess(HwihaTravelRequest(1, HwihaTravelInput.MOVE, b), 41))
    }

    @Test fun `return follows dispatch county and missing assignment is an explicit failure`() {
        val actor = setup()
        assertEquals("NO_RETURN_ASSIGNMENT", service.options(1, HwihaTravelInput.RETURN, 41).code)
        actor.meta = actor.meta + (HwihaCountyAssignment.META_KEY to
            HwihaCountyAssignment("dispatch-1", 3, 1, 2).toMetaValue())
        assertEquals(listOf("B"), service.options(1, HwihaTravelInput.RETURN, 41).destinations.map { it.provinceId })
        assertIs<HwihaTravelAssessment.Eligible>(service.assess(HwihaTravelRequest(1, HwihaTravelInput.RETURN, null), 41))
    }

    @Test fun `admission rejects malformed arguments and controller protects options`() {
        setup()
        val admission = HwihaTravelAdmission(service)
        assertEquals("INVALID_TURN_SLOT", assertFailsWith<HwihaAdmissionDenied> {
            admission.canonicalArguments(HwihaTravelInput.MOVE, 1, 41, 12, "{}") }.code)
        assertEquals("INVALID_INPUT", assertFailsWith<HwihaAdmissionDenied> {
            admission.canonicalArguments(HwihaTravelInput.MOVE, 1, 41, 0,
                """{"destinationProvinceId":"B","destinationProvinceId":"A"}""") }.code)
        assertEquals("""{"destinationProvinceId":"B"}""", admission.canonicalArguments(HwihaTravelInput.MOVE,
            1, 41, 0, """{"destinationProvinceId":"B"}"""))
        val controller = HwihaTravelOptionsController(service)
        assertEquals(401, controller.move(null, 1).statusCode.value())
        assertEquals(403, controller.move(42, 1).statusCode.value())
        assertEquals(200, controller.move(41, 1).statusCode.value())
    }
}
