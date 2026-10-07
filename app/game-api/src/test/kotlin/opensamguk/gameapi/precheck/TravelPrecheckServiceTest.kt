package opensamguk.gameapi.precheck

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Optional
import kotlin.test.*
import org.mockito.Mockito.*
import opensamguk.infra.entity.GameKvEntity
import opensamguk.gameapi.read.*
import opensamguk.gameapi.reserve.*
import opensamguk.gameapi.web.TravelOptionsController
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.logic.input.*
import opensamguk.logic.world.*

class TravelPrecheckServiceTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val retainers = mock(RetainerReadRepository::class.java)
    private val artifacts = mock(ActiveWorldArtifactResolver::class.java)
    private val spatial = mock(SpatialStateReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val mapper = ObjectMapper()
    private val service = TravelPrecheckService(generals, retainers, artifacts, spatial,
        gameKv, diplomacy, mapper)
    private val a = StrategicNodeRef.LandProvince("A")
    private val b = StrategicNodeRef.LandProvince("B")
    private val pin = "a".repeat(64)
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW,
            SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin,
        listOf(LandMarchEdgeMetric("ab", 40_000_000, 40_000_000)))

    private fun setup(forts: List<RoadFort> = emptyList(), hostile: Boolean = false): GeneralReadEntity {
        val actor = GeneralReadEntity(id = 1, worldId = 1, name = "본인", nationId = 1, userId = "41")
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(generals.findAll()).thenReturn(listOf(actor))
        `when`(retainers.findAll()).thenReturn(emptyList())
        `when`(retainers.allBugoks()).thenReturn(emptyList())
        `when`(diplomacy.findAll()).thenReturn(if (hostile) listOf(DiplomacyReadEntity(
            id = 1, worldId = 1, srcNationId = 1, destNationId = 2, stateCode = 0)) else emptyList())
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        `when`(bundle.projection).thenReturn(StrategicRouteProjection(topology, listOf(
            StrategicRouteBinding(1, "r1", "p1", "A"), StrategicRouteBinding(2, "r2", "p2", "B"))))
        `when`(bundle.landMarchMetrics).thenReturn(metrics)
        val world = WorldStateReadEntity(id = 1, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"), meta = mapOf(
            LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
            MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue(),
            RoadFortState.META_KEY to RoadFortState.toMetaValue(forts)))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = 1, worldId = 1), CityReadEntity(id = 2, worldId = 1)), bundle))
        `when`(spatial.readSnapshot(1, topology)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(topology), GeneralPositionSnapshot.fromTopology(topology,
                listOf(GeneralPositionState(topology.topologyRevision, topology.contentHash, 1, a, 1)))))
        return actor
    }

    @Test fun `owner check precedes map reads and options use the shared route assessment`() {
        setup()
        assertFailsWith<TravelReadForbidden> { service.options(1, TravelInput.MOVE, 42) }
        verifyNoInteractions(artifacts, spatial)
        val options = service.options(1, TravelInput.MOVE, 41)
        assertTrue(options.available)
        assertEquals(listOf("A", "B"), options.destinations.map { it.provinceId })
        assertEquals("ALREADY_THERE", options.destinations.first().code)
        val destination = options.destinations.last()
        assertTrue(destination.available)
        assertEquals(DestinationReachability.MULTI_TURN, destination.reachability)
        assertEquals(40_000_000L, destination.distanceMm)
        assertEquals(40_000_000L, destination.costMm)
        assertEquals(2L, destination.estimatedTurns)
        assertFalse(destination.arrivesThisTurn)
        val forced = service.options(1, TravelInput.FORCED_MARCH, 41).destinations.last()
        assertTrue(forced.available)
        assertEquals(DestinationReachability.THIS_TURN, forced.reachability)
        assertEquals(1L, forced.estimatedTurns)
        assertTrue(forced.arrivesThisTurn)
        assertIs<TravelAssessment.Eligible>(service.assess(TravelRequest(1, TravelInput.MOVE, b), 41))
    }

    @Test fun `return follows dispatch county and missing assignment is an explicit failure`() {
        val actor = setup()
        assertEquals("NO_RETURN_ASSIGNMENT", service.options(1, TravelInput.RETURN, 41).code)
        actor.meta = actor.meta + (CountyAssignment.META_KEY to
            CountyAssignment("dispatch-1", 3, 1, 2).toMetaValue())
        assertEquals(listOf("B"), service.options(1, TravelInput.RETURN, 41).destinations.map { it.provinceId })
        assertEquals(DestinationReachability.MULTI_TURN,
            service.options(1, TravelInput.RETURN, 41).destinations.single().reachability)
        assertIs<TravelAssessment.Eligible>(service.assess(TravelRequest(1, TravelInput.RETURN, null), 41))
    }

    @Test fun `hostile fort closes direct travel options and reservation assessment`() {
        setup(hostile = true)
        val fort = RoadFort(RoadFort.siteId("ab", 0, 0), "ab", "A", 0, 0, 2, 100, 100)
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", RoadFortState.META_KEY))
            .thenReturn(GameKvEntity("game_env", "game_env", RoadFortState.META_KEY,
                mapper.writeValueAsString(RoadFortState.toMetaValue(listOf(fort))), worldId = 1))
        val options = service.options(1, TravelInput.MOVE, 41)
        assertFalse(options.available)
        val destination = options.destinations.single { it.provinceId == "B" }
        assertEquals("NO_ROUTE", destination.code)
        assertEquals(DestinationReachability.UNAVAILABLE, destination.reachability)
        assertNull(destination.distanceMm)
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(
            service.assess(TravelRequest(1, TravelInput.MOVE, b), 41)).reason)
    }

    @Test fun `admission rejects malformed arguments and controller protects options`() {
        setup()
        val admission = TravelAdmission(service)
        assertEquals("INVALID_TURN_SLOT", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(TravelInput.MOVE, 1, 41, 12, "{}") }.code)
        assertEquals("INVALID_INPUT", assertFailsWith<AdmissionDenied> {
            admission.canonicalArguments(TravelInput.MOVE, 1, 41, 0,
                """{"destinationProvinceId":"B","destinationProvinceId":"A"}""") }.code)
        assertEquals("""{"destinationProvinceId":"B"}""", admission.canonicalArguments(TravelInput.MOVE,
            1, 41, 0, """{"destinationProvinceId":"B"}"""))
        val controller = TravelOptionsController(service)
        assertEquals(401, controller.move(null, 1).statusCode.value())
        assertEquals(403, controller.move(42, 1).statusCode.value())
        assertEquals(200, controller.move(41, 1).statusCode.value())
    }

    @Test fun `captive travel options and admission deny every personal move with a concrete reason`() {
        val actor = setup()
        val captive = CaptiveState(2, "A", Phase(200, 1, 1), "encounter-1").toMetaValue()
        for (marker in listOf(captive, mapOf("captorGeneralId" to 2), null)) {
            actor.meta = mapOf(CaptiveState.META_KEY to marker)
            for (inputId in TravelInput.INPUT_IDS) {
                val options = service.options(1, inputId, 41)
                assertFalse(options.available)
                assertEquals("STATE_UNAVAILABLE", options.code)
                assertEquals(TravelRules.CAPTIVE_REASON, options.reason)
                assertTrue(options.destinations.isEmpty())
            }
            assertEquals(TravelFailure.STATE_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
                service.assess(TravelRequest(1, TravelInput.MOVE, b), 41)).reason)
            assertEquals(TravelFailure.STATE_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
                service.assess(TravelRequest(1, TravelInput.RETURN, null), 41)).reason)
            assertEquals("STATE_UNAVAILABLE", assertFailsWith<AdmissionDenied> {
                TravelAdmission(service).canonicalArguments(TravelInput.MOVE, 1, 41, 0,
                    """{"destinationProvinceId":"B"}""")
            }.code)
        }
        actor.meta = emptyMap()
        assertTrue(service.options(1, TravelInput.MOVE, 41).available)
    }

    @Test fun `full selected topology travel options bounds route work within one request`() {
        setup()
        val bundle = opensamguk.infra.seed.WorldArtifactsResolver(java.nio.file.Path.of("../.."))
            .artifacts(WorldMapVariant.PROVINCE_WORLD)
        val graph = bundle.projection.topology
        val origin = StrategicNodeRef.LandProvince(
            requireNotNull(bundle.projection.bindingsByCityId.getValue(435).landProvinceId))
        val actor = GeneralReadEntity(id = 1, worldId = 160, name = "Fixture free general",
            nationId = 0, userId = "41", npcState = 0)
        val world = WorldStateReadEntity(id = 160,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"),
            meta = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(graph),
                MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue()))
        `when`(generals.findById(actor.id)).thenReturn(Optional.of(actor))
        `when`(generals.findAll()).thenReturn(listOf(actor))
        val cities = bundle.projection.bindingsByCityId.keys.sorted().map {
            CityReadEntity(id = it, worldId = 160, name = "city-$it")
        }
        val metrics = bundle.landMarchMetrics
        val destinations = graph.landProvinceIds.sorted().map { StrategicNodeRef.LandProvince(it) }
        assertEquals(1428, destinations.size)
        // A graph-sized work budget plus output path lengths is independent of machine speed.
        val expected = StrategicPathResolver.resolveLandMarches(graph,
            destinations.map { StrategicPathRequest(origin, it, 1) },
            requireNotNull(LandPassageState.read(world.meta, graph)), metrics)
        val outputEdges = expected.filterIsInstance<LandMarchPathResult.Resolved>()
            .sumOf { it.path.edgeIds.size.toLong() }
        var metricReads = 0L
        val measuredMap = object : Map<String, LandMarchEdgeMetric> by metrics.edgesById {
            override fun get(key: String): LandMarchEdgeMetric? {
                metricReads++
                return metrics.edgesById[key]
            }
        }
        val measuredMetrics = spy(metrics)
        doReturn(measuredMap).`when`(measuredMetrics).edgesById
        val measuredBundle = spy(bundle)
        doReturn(measuredMetrics).`when`(measuredBundle).landMarchMetrics
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, cities, measuredBundle))
        `when`(spatial.readSnapshot(160, graph)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(graph), GeneralPositionSnapshot.fromTopology(graph,
                listOf(GeneralPositionState(graph.topologyRevision, graph.contentHash, actor.id, origin, 0)))))
        val started = System.nanoTime()
        val result = service.options(actor.id, TravelInput.MOVE, 41)
        val elapsed = java.time.Duration.ofNanos(System.nanoTime() - started)
        val workBudget = 4L * graph.traversalEdges.size + 2L * outputEdges
        println("Travel options: elapsed=$elapsed, heapMaxBytes=${Runtime.getRuntime().maxMemory()}, " +
            "destinations=${result.destinations.size}, metricReads=$metricReads, workBudget=$workBudget")
        assertTrue(result.available)
        assertEquals(destinations.map { it.id }, result.destinations.map { it.provinceId })
        assertTrue(result.destinations.any { it.available && it.estimatedTurns!! > 1 })
        for ((index, route) in expected.withIndex()) {
            val actual = result.destinations[index]
            if (actual.provinceId == origin.id) {
                assertEquals("ALREADY_THERE", actual.code)
            } else if (route is LandMarchPathResult.Resolved) {
                val estimate = MarchDestinationEstimate.of(route.path, metrics,
                    LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
                assertTrue(actual.available)
                assertEquals(estimate.distanceMm, actual.distanceMm)
                assertEquals(estimate.costMm, actual.costMm)
                assertEquals(estimate.estimatedTurns, actual.estimatedTurns)
                assertEquals(estimate.reachability, actual.reachability)
            } else {
                assertEquals("NO_ROUTE", actual.code)
                assertFalse(actual.available)
            }
        }
        assertTrue(metricReads <= workBudget, "Repeated route search: $metricReads reads > $workBudget")
        assertTrue(elapsed < java.time.Duration.ofSeconds(30), "Actual options took $elapsed")
        for (destination in result.destinations.filter { it.available }.take(3)) {
            assertIs<TravelAssessment.Eligible>(service.assess(
                TravelRequest(actor.id, TravelInput.MOVE,
                    StrategicNodeRef.LandProvince(destination.provinceId)), 41))
        }
    }
}
