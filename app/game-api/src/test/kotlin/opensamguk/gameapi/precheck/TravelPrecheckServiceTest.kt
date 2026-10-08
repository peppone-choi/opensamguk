package opensamguk.gameapi.precheck

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Optional
import java.security.MessageDigest
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
    @Test fun `move options arrive in one phase despite long physical distance and terrain cost`() {
        setup()
        val selected = artifacts.resolve()!!
        val bundle = selected.artifacts!!
        `when`(bundle.landMarchMetrics).thenReturn(LandMarchMetricSnapshot(topology, pin,
            listOf(LandMarchEdgeMetric("ab", 200_000_000, 500_000_000))))
        val destination = service.options(1, TravelInput.MOVE, 41).destinations.single { it.provinceId == "B" }
        assertTrue(destination.available)
        assertEquals(200_000_000L, destination.distanceMm)
        assertEquals(500_000_000L, destination.costMm)
        assertEquals(1L, destination.estimatedTurns)
        assertEquals(DestinationReachability.THIS_TURN, destination.reachability)
        assertTrue(destination.arrivesThisTurn)
        assertEquals("""{"destinationProvinceId":"B"}""", TravelAdmission(service).canonicalArguments(
            TravelInput.MOVE, 1, 41, 0, """{"destinationProvinceId":"B"}"""))
        val forced = service.options(1, TravelInput.FORCED_MARCH, 41).destinations.single { it.provinceId == "B" }
        assertEquals(2L, forced.estimatedTurns)
        assertEquals(DestinationReachability.MULTI_TURN, forced.reachability)
        assertFalse(forced.arrivesThisTurn)
        assertEquals(66, forced.forcedFatigueDelta)
        assertEquals(-33, forced.forcedMoraleDelta)
        assertEquals(66, forced.afterFatigue)
        assertEquals(67, forced.afterMorale)
        assertNull(destination.forcedFatigueDelta)
        assertNull(destination.forcedMoraleDelta)
        assertNull(destination.afterFatigue)
        assertNull(destination.afterMorale)
    }

    @Test fun `forced previews and admission reject unpaid raw costs without borrowing a previous actor condition`() {
        val actor = setup()
        actor.meta = mapOf(PersonalTravelCondition.META_KEY to PersonalTravelCondition(87, 6).toMetaValue())
        val eligible = service.options(1, TravelInput.FORCED_MARCH, 41).destinations.single { it.provinceId == "B" }
        assertTrue(eligible.available)
        assertEquals(13, eligible.forcedFatigueDelta)
        assertEquals(-6, eligible.forcedMoraleDelta)
        assertEquals(100, eligible.afterFatigue)
        assertEquals(0, eligible.afterMorale)
        for (condition in listOf(PersonalTravelCondition(88, 100), PersonalTravelCondition(0, 5))) {
            actor.meta = mapOf(PersonalTravelCondition.META_KEY to condition.toMetaValue())
            val blocked = service.options(1, TravelInput.FORCED_MARCH, 41).destinations.single { it.provinceId == "B" }
            assertFalse(blocked.available)
            assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED.name, blocked.code)
            assertNull(blocked.forcedFatigueDelta)
            assertNull(blocked.afterMorale)
            assertEquals(TravelFailure.FORCED_MARCH_EXHAUSTED.name, assertFailsWith<AdmissionDenied> {
                TravelAdmission(service).canonicalArguments(TravelInput.FORCED_MARCH, 1, 41, 0,
                    """{"destinationProvinceId":"B"}""")
            }.code)
            assertTrue(service.options(1, TravelInput.MOVE, 41).destinations.single { it.provinceId == "B" }.available)
            assertEquals(condition.toMetaValue(), actor.meta[PersonalTravelCondition.META_KEY])
        }
    }

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
    private val terrain = """{"provinceRecords":[{"id":"A","displayName":"출발 구역"},
        {"id":"B","displayName":"목적 구역"}]}""".toByteArray()
    private val pin = sha(terrain)
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(TraversalEdge("ab", a, b, TraversalMode.LAND, false, 1, 10, RiskBand.LOW,
            SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
    private val metrics = LandMarchMetricSnapshot(topology, pin,
        listOf(LandMarchEdgeMetric("ab", 40_000_000, 40_000_000)))
    private lateinit var fixtureBundle: ResolvedWorldArtifacts

    private fun setup(forts: List<RoadFort> = emptyList(), hostile: Boolean = false,
        tiles: ByteArray = terrain, worldId: Int = 1, bindDestination: Boolean = true): GeneralReadEntity {
        val graph = if (tiles.contentEquals(terrain)) topology else StrategicTopologySnapshot(
            topology.topologyRevision, topology.landProvinceIds, topology.waterZones,
            topology.traversalEdges, topology.riverBarriers,
            mapOf(LandMarchMetricSnapshot.TILES_PATH to sha(tiles)))
        val actor = GeneralReadEntity(id = 1, worldId = worldId, name = "본인", nationId = 1, userId = "41")
        `when`(generals.findById(1)).thenReturn(Optional.of(actor))
        `when`(generals.findAll()).thenReturn(listOf(actor))
        `when`(retainers.findAll()).thenReturn(emptyList())
        `when`(retainers.allBugoks()).thenReturn(emptyList())
        `when`(diplomacy.findAll()).thenReturn(if (hostile) listOf(DiplomacyReadEntity(
            id = 1, worldId = 1, srcNationId = 1, destNationId = 2, stateCode = 0)) else emptyList())
        val bundle = mock(ResolvedWorldArtifacts::class.java)
        fixtureBundle = bundle
        `when`(bundle.variant).thenReturn(WorldMapVariant.PROVINCE_WORLD)
        `when`(bundle.artifactBytes(ProvinceNamesCache.TILES_PATH)).thenReturn(tiles)
        `when`(bundle.projection).thenReturn(StrategicRouteProjection(graph, listOf(
            StrategicRouteBinding(1, "r1", "p1", "A"), StrategicRouteBinding(2, "r2", "p2",
                if (bindDestination) "B" else null))))
        val selectedMetrics = if (graph === topology) metrics else LandMarchMetricSnapshot(graph,
            sha(tiles), listOf(LandMarchEdgeMetric("ab", 40_000_000, 40_000_000)))
        `when`(bundle.landMarchMetrics).thenReturn(selectedMetrics)
        val world = WorldStateReadEntity(id = worldId, config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"), meta = mapOf(
            LandPassageState.META_KEY to LandPassageState.initialMetaValue(graph),
            MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue(),
            RoadFortState.META_KEY to RoadFortState.toMetaValue(forts)))
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world,
            listOf(CityReadEntity(id = 1, worldId = worldId, name = "런타임 현 이름"),
                CityReadEntity(id = 2, worldId = worldId, name = "다른 현 이름")), bundle))
        `when`(spatial.readSnapshot(worldId, graph)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(graph), GeneralPositionSnapshot.fromTopology(graph,
                listOf(GeneralPositionState(graph.topologyRevision, graph.contentHash, 1, a, 1)))))
        return actor
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `selected pinned labels cover destinations without a runtime city binding`() {
        setup(bindDestination = false)
        val options = service.options(1, TravelInput.MOVE, 41)
        assertEquals(listOf("출발 구역", "목적 구역"), options.destinations.map { it.name })
        assertTrue(options.destinations.last().available)
        assertEquals(40_000_000L, options.destinations.last().costMm)
        verify(artifacts, times(1)).resolve()
        service.options(1, TravelInput.FORCED_MARCH, 41)
        verify(fixtureBundle, times(1)).artifactBytes(ProvinceNamesCache.TILES_PATH)
    }

    @Test fun `world and source changes cannot reuse a previous destination label`() {
        setup()
        assertEquals("목적 구역", service.options(1, TravelInput.MOVE, 41).destinations.last().name)
        setup(worldId = 2)
        assertEquals("목적 구역", service.options(1, TravelInput.MOVE, 41).destinations.last().name)
        verify(fixtureBundle, times(1)).artifactBytes(ProvinceNamesCache.TILES_PATH)
        setup(worldId = 2, tiles = String(terrain).replace("목적 구역", "새 세계 목적지").toByteArray())
        assertEquals("새 세계 목적지", service.options(1, TravelInput.MOVE, 41).destinations.last().name)
        verify(fixtureBundle, times(1)).artifactBytes(ProvinceNamesCache.TILES_PATH)
    }

    @Test fun `invalid pinned label sources produce an explicit unavailable response and do not affect admission`() {
        val badSources = listOf(
            """{"provinceRecords":[{"id":"A","displayName":"출발"}]}""",
            """{"provinceRecords":[{"id":"A","displayName":"출발"},{"id":"B","displayName":""}]}""",
            """{"provinceRecords":[{"id":"A","displayName":"출발"},{"id":"A","displayName":"중복"}]}""",
            """{"provinceRecords":[]} trailing""",
        )
        for (source in badSources) {
            setup(tiles = source.toByteArray())
            val options = service.options(1, TravelInput.MOVE, 41)
            assertFalse(options.available)
            assertEquals("STATE_UNAVAILABLE", options.code)
            assertEquals(TravelFailure.STATE_UNAVAILABLE.message, options.reason)
            assertTrue(options.destinations.isEmpty())
            assertIs<TravelAssessment.Eligible>(service.assess(TravelRequest(1, TravelInput.MOVE, b), 41))
        }
        setup()
        `when`(fixtureBundle.artifactBytes(ProvinceNamesCache.TILES_PATH)).thenReturn("{}".toByteArray())
        assertEquals("STATE_UNAVAILABLE", service.options(1, TravelInput.MOVE, 41).code)
        `when`(fixtureBundle.artifactBytes(ProvinceNamesCache.TILES_PATH)).thenReturn(terrain)
        assertTrue(service.options(1, TravelInput.MOVE, 41).available)
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
        assertEquals(DestinationReachability.THIS_TURN, destination.reachability)
        assertEquals(40_000_000L, destination.distanceMm)
        assertEquals(40_000_000L, destination.costMm)
        assertEquals(1L, destination.estimatedTurns)
        assertTrue(destination.arrivesThisTurn)
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
        val options = service.options(1, TravelInput.RETURN, 41)
        assertEquals(DestinationReachability.THIS_TURN, options.destinations.single().reachability)
        assertEquals(1L, options.destinations.single().estimatedTurns)
        assertEquals("B", options.workplace!!.provinceId)
        assertEquals("목적 구역", options.workplace!!.name)
        assertEquals(2, options.workplace!!.countyId)
        assertIs<TravelAssessment.Eligible>(service.assess(TravelRequest(1, TravelInput.RETURN, null), 41))
    }

    @Test fun `return projects the next edge separately from ultimate workplace and rechecks closure`() {
        val names = """{"provinceRecords":[{"id":"A","displayName":"출발 구역"},
            {"id":"B","displayName":"근무 구역"},{"id":"C","displayName":"이번 이웃"}]}""".toByteArray()
        val actor = setup(tiles = names)
        actor.meta = actor.meta + (CountyAssignment.META_KEY to CountyAssignment("return-chain", 3, 1, 2).toMetaValue())
        val c = StrategicNodeRef.LandProvince("C")
        fun edge(id: String, from: StrategicNodeRef.LandProvince, to: StrategicNodeRef.LandProvince) =
            TraversalEdge(id, from, to, TraversalMode.LAND, false, 1, 10, RiskBand.LOW,
                SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)
        val graph = StrategicTopologySnapshot("return-chain", setOf("A", "B", "C"), emptyList(),
            listOf(edge("ac", a, c), edge("cb", c, b)), emptyList(),
            mapOf(LandMarchMetricSnapshot.TILES_PATH to sha(names)))
        val rough = LandMarchMetricSnapshot(graph, sha(names), listOf(
            LandMarchEdgeMetric("ac", 200_000_000, 500_000_000), LandMarchEdgeMetric("cb", 10_000_000, 10_000_000)))
        `when`(fixtureBundle.projection).thenReturn(StrategicRouteProjection(graph, listOf(
            StrategicRouteBinding(1, "r1", "p1", "A"), StrategicRouteBinding(2, "r2", "p2", "B"))))
        `when`(fixtureBundle.landMarchMetrics).thenReturn(rough)
        `when`(spatial.readSnapshot(1, graph)).thenReturn(SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(graph), GeneralPositionSnapshot.fromTopology(graph,
                listOf(GeneralPositionState(graph.topologyRevision, graph.contentHash, 1, a, 1)))))
        fun passage(closed: Boolean) {
            val raw = LandPassageState.initialMetaValue(graph) + ("edges" to graph.traversalEdges.associate {
                it.id to mapOf("active" to (!closed || it.id != "ac"), "seasonOpen" to true,
                    "blockaded" to false, "availableCapacity" to it.capacity)
            })
            `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", LandPassageState.META_KEY))
                .thenReturn(GameKvEntity("game_env", "game_env", LandPassageState.META_KEY,
                    mapper.writeValueAsString(raw), worldId = 1))
        }
        passage(false)
        val options = service.options(1, TravelInput.RETURN, 41)
        assertTrue(options.available)
        assertEquals(TravelWorkplace("B", "근무 구역", 2), options.workplace)
        val next = options.destinations.single()
        assertEquals("C", next.provinceId)
        assertEquals("이번 이웃", next.name)
        assertEquals(1L, next.estimatedTurns)
        assertEquals(200_000_000L, next.distanceMm)
        assertEquals(500_000_000L, next.costMm)
        assertTrue(next.arrivesThisTurn)
        assertEquals(listOf("ac"), assertIs<TravelAssessment.Eligible>(
            service.assess(TravelRequest(1, TravelInput.RETURN, null), 41)).path.edgeIds)
        passage(true)
        val blocked = service.options(1, TravelInput.RETURN, 41)
        assertFalse(blocked.available)
        assertEquals(TravelFailure.NO_ROUTE.name, blocked.code)
        assertEquals(emptyList(), blocked.destinations)
        assertEquals(options.workplace, blocked.workplace)
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(
            service.assess(TravelRequest(1, TravelInput.RETURN, null), 41)).reason)
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
        // Later QA position witness; the origin of the first timed-out request was not captured.
        val origin = StrategicNodeRef.LandProvince(
            requireNotNull(bundle.projection.bindingsByCityId.getValue(1003).landProvinceId))
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
        // A future map change must preserve the premise that a legal adjacent forced move
        // fits one phase; a cap change needs a new owner decision, never a silent bypass.
        for (edge in graph.traversalEdges.filter(LandMarchMetricSnapshot::supports)) {
            val leg = metrics.edgesById.getValue(edge.id)
            assertTrue(leg.distanceMm <= ForcedMarchTempo.maxRouteDistanceMm,
                "Direct edge ${edge.id} exceeds the approved forced distance cap")
            assertTrue(leg.costMm <= ForcedMarchTempo.budgetMm,
                "Direct edge ${edge.id} would make forced movement slower than ordinary MOVE")
        }
        val destinations = graph.landProvinceIds.sorted().map { StrategicNodeRef.LandProvince(it) }
        assertEquals(1428, bundle.projection.bindingsByCityId.size)
        assertEquals(1608, destinations.size)
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
        // Count real map reads without retaining millions of Mockito getter invocations.
        val measuredMetrics = mock(LandMarchMetricSnapshot::class.java,
            withSettings().spiedInstance(metrics).defaultAnswer(CALLS_REAL_METHODS).stubOnly())
        doReturn(measuredMap).`when`(measuredMetrics).edgesById
        val measuredBundle = spy(bundle)
        doReturn(measuredMetrics).`when`(measuredBundle).landMarchMetrics
        `when`(artifacts.resolve()).thenReturn(ActiveWorldArtifactSnapshot(world, cities, measuredBundle))
        val positions = SpatialStateReadSnapshot(
            ProvinceControlSnapshot.fromTopology(graph), GeneralPositionSnapshot.fromTopology(graph,
                listOf(GeneralPositionState(graph.topologyRevision, graph.contentHash, actor.id, origin,
                    revision = 1))))
        `when`(spatial.readSnapshot(160, graph)).thenReturn(positions)
        val started = System.nanoTime()
        val result = service.options(actor.id, TravelInput.FORCED_MARCH, 41)
        val elapsed = java.time.Duration.ofNanos(System.nanoTime() - started)
        val workBudget = 4L * graph.traversalEdges.size + 2L * outputEdges
        println("Travel options: elapsed=$elapsed, heapMaxBytes=${Runtime.getRuntime().maxMemory()}, " +
            "destinations=${result.destinations.size}, metricReads=$metricReads, workBudget=$workBudget")
        assertTrue(result.available)
        assertEquals(destinations.map { it.id }, result.destinations.map { it.provinceId })
        val expectedNames = mapper.readTree(bundle.artifactBytes(ProvinceNamesCache.TILES_PATH))["provinceRecords"]
            .associate { it["id"].asText() to it["displayName"].asText() }
        assertEquals(1608, expectedNames.size)
        assertEquals(expectedNames, result.destinations.associate { it.provinceId to it.name })
        assertTrue(result.destinations.any { it.available && it.estimatedTurns == 1L })
        val policies = TravelRules.assessMany(destinations.map {
            TravelRequest(actor.id, TravelInput.FORCED_MARCH, it) to it
        }, TravelSnapshot(RuleProfile.HWIHA, true, origin, false, false, emptySet(), actor.meta),
            graph, metrics, world.meta)
        for ((index, assessment) in policies.withIndex()) {
            val actual = result.destinations[index]
            if (assessment is TravelAssessment.Eligible) {
                val preview = assessment.forcedPreview!!
                assertTrue(actual.available)
                assertTrue(preview.distanceMm <= 260_000_000L)
                assertTrue(preview.estimatedTurns in 1L..2L)
                assertEquals(preview.distanceMm, actual.distanceMm)
                assertEquals(assessment.path.totalCostMm, actual.costMm)
                assertEquals(preview.estimatedTurns, actual.estimatedTurns)
                assertEquals(preview.afterFatigue, actual.afterFatigue)
                assertEquals(preview.afterMorale, actual.afterMorale)
                val route = expected[index]
                if (route is LandMarchPathResult.Resolved &&
                    PersonalForcedMarchPolicy.routeFailure(route.path, metrics) == null) {
                    // Preserve the independent global shortest-route cost oracle whenever it is legal.
                    assertEquals(route.path.totalCostMm, actual.costMm)
                } else assertEquals(1, assessment.path.edgeIds.size)
            } else {
                assertEquals((assessment as TravelAssessment.Rejected).reason.name, actual.code)
                assertFalse(actual.available)
                assertNull(actual.forcedFatigueDelta)
                assertNull(actual.forcedMoraleDelta)
                assertNull(actual.afterFatigue)
                assertNull(actual.afterMorale)
            }
        }
        assertTrue(metricReads <= workBudget, "Repeated route search: $metricReads reads > $workBudget")
        assertTrue(elapsed < java.time.Duration.ofSeconds(30), "Actual options took $elapsed")
        for (destination in result.destinations.filter { it.available }.take(3)) {
            assertIs<TravelAssessment.Eligible>(service.assess(
                TravelRequest(actor.id, TravelInput.FORCED_MARCH,
                    StrategicNodeRef.LandProvince(destination.provinceId)), 41))
        }
        metricReads = 0
        val moveStarted = System.nanoTime()
        val move = service.options(actor.id, TravelInput.MOVE, 41)
        val moveElapsed = java.time.Duration.ofNanos(System.nanoTime() - moveStarted)
        val passage = requireNotNull(LandPassageState.read(world.meta, graph))
        val directNeighbors = graph.traversalEdges.filter(LandMarchMetricSnapshot::supports).mapNotNull { edge ->
            val target = when {
                edge.from == origin -> edge.to
                !edge.directed && edge.to == origin -> edge.from
                else -> return@mapNotNull null
            }
            target.takeIf { it in StrategicPathResolver.reachableNodes(graph, setOf(origin), passage, 1,
                { node -> node == origin || node == target }, { candidate -> candidate.id == edge.id }) }
        }.toSet()
        assertTrue(move.available)
        assertEquals(destinations.map { it.id }, move.destinations.map { it.provinceId })
        assertEquals(expectedNames, move.destinations.associate { it.provinceId to it.name })
        assertEquals(directNeighbors.mapTo(sortedSetOf()) { (it as StrategicNodeRef.LandProvince).id },
            move.destinations.filter { it.available }.mapTo(sortedSetOf()) { it.provinceId })
        for (destination in move.destinations.filter { it.available }) {
            assertEquals(1L, destination.estimatedTurns)
            assertEquals(DestinationReachability.THIS_TURN, destination.reachability)
            assertTrue(destination.arrivesThisTurn)
            val route = assertIs<TravelAssessment.Eligible>(service.assess(TravelRequest(actor.id,
                TravelInput.MOVE, StrategicNodeRef.LandProvince(destination.provinceId)), 41)).path
            assertEquals(1, route.edgeIds.size)
            val edge = metrics.edgesById.getValue(route.edgeIds.single())
            assertEquals(edge.distanceMm, destination.distanceMm)
            assertEquals(edge.costMm, destination.costMm)
            if (edge.distanceMm <= 260_000_000L) {
                val forced = result.destinations.single { it.provinceId == destination.provinceId }
                assertTrue(forced.available, "Direct neighbor lost forced availability: ${destination.provinceId}")
                assertEquals(1L, forced.estimatedTurns, "Direct neighbor forced arrival is slower than MOVE")
            }
        }
        assertTrue(metricReads <= workBudget, "Repeated direct search: $metricReads reads > $workBudget")
        assertTrue(moveElapsed < java.time.Duration.ofSeconds(30), "Actual move options took $moveElapsed")
    }
}
