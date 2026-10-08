package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import opensamguk.logic.world.*

class TravelRulesTest {
    @Test fun `malformed personal condition closes forced travel without contaminating move or return batch results`() {
        val bad = snapshot.copy(actorMeta = mapOf(PersonalTravelCondition.META_KEY to mapOf("version" to 1)))
        val queries = listOf(request to destination, request.copy(inputId = TravelInput.FORCED_MARCH) to destination,
            request.copy(inputId = TravelInput.RETURN, destination = null) to destination)
        val result = TravelRules.assessMany(queries, bad, topology, metrics, meta)
        assertIs<TravelAssessment.Eligible>(result[0])
        assertEquals(TravelAssessment.Rejected(TravelFailure.STATE_UNAVAILABLE), result[1])
        assertIs<TravelAssessment.Eligible>(result[2])
    }

    @Test fun `move uses one direct edge even when an indirect route is cheaper`() {
        fun node(id: String) = StrategicNodeRef.LandProvince(id)
        fun edge(id: String, from: String, to: String, mode: TraversalMode = TraversalMode.LAND,
            directed: Boolean = false, season: SeasonalAvailability = SeasonalAvailability.ALWAYS) =
            TraversalEdge(id, node(from), node(to), mode, directed, 1, 7, RiskBand.LOW, season,
                sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)
        val edges = listOf(edge("ab", "A", "B"), edge("ac", "A", "C"), edge("cb", "C", "B"),
            edge("bd", "B", "D"),
            edge("af", "A", "F", mode = TraversalMode.FERRY),
            edge("ag", "A", "G", mode = TraversalMode.FORD),
            edge("ah", "A", "H", mode = TraversalMode.BRIDGE),
            edge("ai", "A", "I", season = SeasonalAvailability.SEASONAL))
        val graph = StrategicTopologySnapshot("direct", ('A'..'I').mapTo(sortedSetOf()) { it.toString() },
            emptyList(), edges, emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
        val costs = LandMarchMetricSnapshot(graph, "a".repeat(64),
            edges.filter(LandMarchMetricSnapshot::supports).map {
                LandMarchEdgeMetric(it.id, if (it.id == "ab") 200_000_000 else 1,
                    if (it.id == "ab") 500_000_000 else 1)
            })
        val graphMeta = meta + (LandPassageState.META_KEY to LandPassageState.initialMetaValue(graph))
        fun evaluate(id: String, inputId: String = TravelInput.MOVE, liveMeta: Map<String, Any?> = graphMeta) =
            TravelRules.assess(request.copy(inputId = inputId, destination = node(id)), node(id),
                snapshot, graph, costs, liveMeta)
        assertEquals(listOf("ab"), assertIs<TravelAssessment.Eligible>(evaluate("B")).path.edgeIds)
        assertEquals(500_000_000L, assertIs<TravelAssessment.Eligible>(evaluate("B")).path.totalCostMm)
        assertEquals(listOf("ac", "cb"), assertIs<TravelAssessment.Eligible>(
            evaluate("B", TravelInput.FORCED_MARCH)).path.edgeIds)
        for (id in listOf("D", "E", "F", "I"))
            assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(evaluate(id)).reason)
        for (id in listOf("G", "H")) assertIs<TravelAssessment.Eligible>(evaluate(id))
        val passage = LandPassageState.initialMetaValue(graph)
        @Suppress("UNCHECKED_CAST")
        val rows = passage.getValue("edges") as Map<String, Map<String, Any>>
        for (closure in listOf(mapOf("active" to false), mapOf("blockaded" to true),
                mapOf("availableCapacity" to 0))) {
            val closed = graphMeta + (LandPassageState.META_KEY to
                (passage + ("edges" to (rows + ("ab" to (rows.getValue("ab") + closure))))))
            // The open A-C-B detour must never substitute for the closed adjacent edge.
            assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(evaluate("B", liveMeta = closed)).reason)
            assertIs<TravelAssessment.Eligible>(evaluate("B", TravelInput.FORCED_MARCH, closed))
        }
        // Current topology rejects directed LAND/FORD/BRIDGE edges before travel assessment.
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            StrategicTopologySnapshot("directed-land", setOf("A", "E"), emptyList(),
                listOf(edge("ea", "E", "A", directed = true)), emptyList(), emptyMap())
        }
        assertIs<TravelAssessment.Eligible>(TravelRules.assess(request.copy(destination = node("A")), node("A"),
            snapshot.copy(actorNode = node("B")), graph, costs, graphMeta))
    }

    @Test fun `move cannot cross a river barrier without an open reviewed ford`() {
        val ford = topology.traversalEdges.single().copy(id = "ford", mode = TraversalMode.FORD)
        val graph = StrategicTopologySnapshot("river", setOf("A", "B"), emptyList(),
            topology.traversalEdges + ford, listOf(RiverBarrier("river", "A", "B", listOf("qa"),
                EvidenceConfidence.REVIEWED)), mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
        val costs = LandMarchMetricSnapshot(graph, "a".repeat(64), listOf(
            LandMarchEdgeMetric("ab", 1, 1), LandMarchEdgeMetric("ford", 1, 200_000_000)))
        val passage = LandPassageState.initialMetaValue(graph)
        val open = meta + (LandPassageState.META_KEY to passage)
        assertEquals(listOf("ford"), assertIs<TravelAssessment.Eligible>(TravelRules.assess(
            request, destination, snapshot, graph, costs, open)).path.edgeIds)
        @Suppress("UNCHECKED_CAST")
        val rows = passage.getValue("edges") as Map<String, Map<String, Any>>
        val closed = open + (LandPassageState.META_KEY to (passage +
            ("edges" to (rows + ("ford" to (rows.getValue("ford") + ("active" to false)))))))
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(TravelRules.assess(
            request, destination, snapshot, graph, costs, closed)).reason)
    }

    @Test fun `travel ledger failure reasons cover admission and execution exactly`() {
        val expected = TravelFailure.entries.filter { it !in PersonalTravelPolicyHold.REASONS }.mapTo(sortedSetOf()) { it.name } +
            setOf("UNKNOWN_INPUT", "UNAUTHORIZED", "FORBIDDEN", "INVALID_TURN_SLOT")
        val catalog = InputCatalog.load()
        for (id in TravelInput.INPUT_IDS) {
            val specific = when (id) {
                TravelInput.MOVE, TravelInput.RETURN -> setOf(TravelFailure.TRAVEL_POLICY_CHANGED.name)
                TravelInput.FORCED_MARCH -> PersonalTravelPolicyHold.REASONS.mapTo(sortedSetOf()) { it.name }
                else -> emptySet()
            }
            assertEquals(expected + specific, catalog[id]!!.failureReasons.toSet(), id)
            assertEquals(InputDeliveryState.UI_READY, catalog[id]!!.deliveryState)
        }
    }

    private val origin = StrategicNodeRef.LandProvince("A")
    private val destination = StrategicNodeRef.LandProvince("B")
    private val topology = StrategicTopologySnapshot("qa", setOf("A", "B"), emptyList(),
        listOf(TraversalEdge("ab", origin, destination, TraversalMode.LAND, false, 1, 7,
            RiskBand.LOW, SeasonalAvailability.ALWAYS, sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)),
        emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to "a".repeat(64)))
    private val metrics = LandMarchMetricSnapshot(topology, "a".repeat(64),
        listOf(LandMarchEdgeMetric("ab", 40_000_000, 40_000_000)))
    private val meta = mapOf(LandPassageState.META_KEY to LandPassageState.initialMetaValue(topology),
        MarchReactions.META_KEY to MarchReactions.Empty.toMetaValue())
    private val snapshot = TravelSnapshot(RuleProfile.HWIHA, true, origin, false, false, setOf(2), emptyMap())
    private val request = TravelRequest(1, TravelInput.MOVE, destination)

    private fun assess(request: TravelRequest = this.request, destination: StrategicNodeRef.LandProvince? = this.destination,
        snapshot: TravelSnapshot = this.snapshot, meta: Map<String, Any?> = this.meta) =
        TravelRules.assess(request, destination, snapshot, topology, metrics, meta)

    private fun assertSameAssessments(expected: List<TravelAssessment>, actual: List<TravelAssessment>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (first, second) ->
            if (first is TravelAssessment.Rejected) assertEquals(first, second)
            else {
                val path = assertIs<TravelAssessment.Eligible>(first).path
                val other = assertIs<TravelAssessment.Eligible>(second).path
                // ResolvedLandMarchPath is an immutable class with reference equality.
                assertEquals(path.nodeKeys, other.nodeKeys)
                assertEquals(path.edgeIds, other.edgeIds)
                assertEquals(path.modes, other.modes)
                assertEquals(path.totalCostMm, other.totalCostMm)
                assertEquals(path.capacity, other.capacity)
                assertEquals(path.topologyRevision, other.topologyRevision)
                assertEquals(path.topologyHash, other.topologyHash)
                assertEquals(path.metricHash, other.metricHash)
                assertEquals(path.pathHash, other.pathHash)
            }
        }
    }

    @Test fun `route assessment uses the current land position and executable passage`() {
        assertEquals(listOf("land:A", "land:B"), assertIs<TravelAssessment.Eligible>(assess()).path.nodeKeys)
        val closed = LandPassageState.initialMetaValue(topology) + ("edges" to mapOf("ab" to
            mapOf("active" to true, "seasonOpen" to false, "blockaded" to true, "availableCapacity" to 7)))
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(
            assess(meta = meta + (LandPassageState.META_KEY to closed))).reason)
        assertEquals(TravelFailure.POSITION_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
            assess(snapshot = snapshot.copy(actorNode = null))).reason)
    }

    @Test fun `arrival estimate uses terrain cost while exposing physical distance`() {
        val rough = LandMarchMetricSnapshot(topology, "a".repeat(64),
            listOf(LandMarchEdgeMetric("ab", 40_000_000, 60_000_000)))
        val passage = LandPassageState.read(meta, topology)!!
        val path = assertIs<LandMarchPathResult.Resolved>(StrategicPathResolver.resolveLandMarch(
            topology, StrategicPathRequest(origin, destination, 1), passage, rough)).path
        val normal = MarchDestinationEstimate.of(path, rough, LandMarchMetricSnapshot.NORMAL_BUDGET_MM)
        assertEquals(40_000_000L, normal.distanceMm)
        assertEquals(60_000_000L, normal.costMm)
        assertEquals(2L, normal.estimatedTurns)
        assertEquals(DestinationReachability.MULTI_TURN, normal.reachability)
        assertEquals(1L, MarchDestinationEstimate.of(path, rough, ForcedMarchTempo.budgetMm).estimatedTurns)
    }

    @Test fun `shared assessment fails closed on conflict and missing authority`() {
        for ((state, failure) in listOf(
            snapshot.copy(profile = RuleProfile.SAMMO) to TravelFailure.WRONG_RULE_PROFILE,
            snapshot.copy(actorExists = false) to TravelFailure.ACTOR_NOT_FOUND,
            snapshot.copy(inBattle = true) to TravelFailure.BATTLE_PENDING,
            snapshot.copy(commandsCorps = true) to TravelFailure.CORPS_DEPLOYED,
        )) assertEquals(failure, assertIs<TravelAssessment.Rejected>(assess(snapshot = state)).reason)
        assertEquals(TravelFailure.INVALID_DESTINATION, assertIs<TravelAssessment.Rejected>(
            assess(request = request.copy(destination = StrategicNodeRef.LandProvince("missing")),
                destination = StrategicNodeRef.LandProvince("missing"))).reason)
        assertEquals(TravelFailure.ALREADY_THERE, assertIs<TravelAssessment.Rejected>(
            assess(request = request.copy(destination = origin), destination = origin)).reason)
        assertEquals(TravelFailure.INVALID_INPUT, assertIs<TravelAssessment.Rejected>(
            assess(destination = origin)).reason)
        assertEquals(TravelFailure.STATE_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
            assess(meta = emptyMap())).reason)
    }

    @Test fun `captive marker blocks travel before route state and free actors keep their route`() {
        val captive = CaptiveState(2, "A", Phase(200, 1, 1), "encounter-1").toMetaValue()
        for (marker in listOf(captive, mapOf("captorGeneralId" to 2), null)) {
            val held = snapshot.copy(actorMeta = mapOf(CaptiveState.META_KEY to marker))
            assertEquals(TravelFailure.STATE_UNAVAILABLE, assertIs<TravelAssessment.Rejected>(
                assess(snapshot = held)).reason)
            assertEquals(TravelFailure.STATE_UNAVAILABLE, TravelRules.actorFailure(held))
        }
        assertIs<TravelAssessment.Eligible>(assess())
        assertEquals(TravelFailure.WRONG_RULE_PROFILE, assertIs<TravelAssessment.Rejected>(
            assess(snapshot = snapshot.copy(profile = RuleProfile.entries.first { it != RuleProfile.HWIHA }))).reason)
    }

    @Test fun `hostile road fort blocks direct travel but friendly or neutral fort does not`() {
        val fort = RoadFort(RoadFort.siteId("ab", 0, 0), "ab", "A", 0, 0, 2, 100, 100)
        val fortified = meta + (RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort)))
        assertEquals(TravelFailure.NO_ROUTE, assertIs<TravelAssessment.Rejected>(
            assess(meta = fortified)).reason)
        assertIs<TravelAssessment.Eligible>(assess(snapshot = snapshot.copy(hostileNationIds = emptySet()),
            meta = fortified))
        assertIs<TravelAssessment.Eligible>(assess(meta = meta + (RoadFortState.META_KEY to
            RoadFortState.toMetaValue(listOf(fort.copy(ownerNationId = 1))))))
    }

    @Test fun `batch keeps ordered input and actor failures ahead of route state`() {
        val missing = StrategicNodeRef.LandProvince("missing")
        val queries = listOf(request to destination, request.copy(actorId = 0) to destination,
            request.copy(inputId = "unknown") to destination, request to null, request to origin,
            request.copy(destination = missing) to missing, request.copy(destination = origin) to origin,
            request.copy(inputId = TravelInput.RETURN, destination = null) to destination,
            request.copy(inputId = TravelInput.FORCED_MARCH) to destination, request to destination)
        fun expected(actorFailure: TravelFailure? = null) = queries.indices.map { index ->
            TravelAssessment.Rejected(when {
                index in 1..2 -> TravelFailure.INVALID_INPUT
                actorFailure != null -> actorFailure
                index in 3..4 -> TravelFailure.INVALID_INPUT
                index == 5 -> TravelFailure.INVALID_DESTINATION
                index == 6 -> TravelFailure.ALREADY_THERE
                else -> TravelFailure.STATE_UNAVAILABLE
            })
        }
        assertEquals(expected(), TravelRules.assessMany(queries, snapshot, topology, metrics, emptyMap()))
        val states = listOf(snapshot.copy(actorExists = false) to TravelFailure.ACTOR_NOT_FOUND,
            snapshot.copy(actorNode = null) to TravelFailure.POSITION_UNAVAILABLE,
            snapshot.copy(inBattle = true) to TravelFailure.BATTLE_PENDING,
            snapshot.copy(commandsCorps = true) to TravelFailure.CORPS_DEPLOYED) +
            listOf(null, mapOf("captorGeneralId" to 2)).map { marker ->
                snapshot.copy(actorMeta = mapOf(CaptiveState.META_KEY to marker)) to TravelFailure.STATE_UNAVAILABLE
            }
        for ((state, reason) in states) {
            val results = TravelRules.assessMany(queries, state, topology, metrics, emptyMap())
            val ordered = expected(reason).toMutableList()
            // Destination argument checks precede position/battle/deployment, but follow actor/captive checks.
            if (reason in setOf(TravelFailure.POSITION_UNAVAILABLE, TravelFailure.BATTLE_PENDING,
                    TravelFailure.CORPS_DEPLOYED)) {
                ordered[3] = TravelAssessment.Rejected(TravelFailure.INVALID_INPUT)
                ordered[4] = TravelAssessment.Rejected(TravelFailure.INVALID_INPUT)
            }
            assertEquals(ordered, results)
            assertEquals(queries.map { (query, target) -> assess(query, target, state, emptyMap()) }, results)
        }
        assertEquals(List(queries.size) { TravelAssessment.Rejected(TravelFailure.WRONG_RULE_PROFILE) },
            TravelRules.assessMany(queries, snapshot.copy(profile = RuleProfile.entries.first {
                it != RuleProfile.HWIHA }), topology, metrics, meta))
        assertEquals(emptyList(), TravelRules.assessMany(emptyList(), snapshot, topology, metrics, emptyMap()))
    }

    @Test fun `batch preserves passage metadata and hostile fort denials without stale reuse`() {
        val queries = listOf(request to destination, request.copy(inputId = TravelInput.RETURN,
            destination = null) to destination, request.copy(inputId = TravelInput.FORCED_MARCH) to destination,
            request.copy(destination = origin) to origin, request to destination)
        val fort = RoadFort(RoadFort.siteId("ab", 0, 0), "ab", "A", 0, 0, 2, 100, 100)
        val closed = LandPassageState.initialMetaValue(topology) + ("edges" to mapOf("ab" to
            mapOf("active" to true, "seasonOpen" to false, "blockaded" to true, "availableCapacity" to 7)))
        val states = listOf(
            meta to null,
            meta + (LandPassageState.META_KEY to closed) to TravelFailure.NO_ROUTE,
            meta + (RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort))) to TravelFailure.NO_ROUTE,
            meta - MarchReactions.META_KEY to TravelFailure.STATE_UNAVAILABLE,
            meta + (MarchReactions.META_KEY to mapOf("version" to -1)) to TravelFailure.STATE_UNAVAILABLE,
            meta + (LandPassageState.META_KEY to mapOf("version" to -1)) to TravelFailure.STATE_UNAVAILABLE,
            meta + (RoadFortState.META_KEY to mapOf("version" to -1)) to TravelFailure.STATE_UNAVAILABLE,
            meta to null,
        )
        for ((worldMeta, failure) in states) {
            val results = TravelRules.assessMany(queries, snapshot, topology, metrics, worldMeta)
            assertSameAssessments(queries.map { (query, target) -> assess(query, target, meta = worldMeta) }, results)
            for (index in listOf(0, 1, 2, 4)) {
                if (failure == null) {
                    val path = assertIs<TravelAssessment.Eligible>(results[index]).path
                    assertEquals(listOf("ab"), path.edgeIds)
                    assertEquals(40_000_000L, path.totalCostMm)
                } else assertEquals(failure, assertIs<TravelAssessment.Rejected>(results[index]).reason)
            }
            assertEquals(TravelAssessment.Rejected(TravelFailure.ALREADY_THERE), results[3])
        }
        val friendly = TravelRules.assessMany(queries, snapshot.copy(hostileNationIds = emptySet()),
            topology, metrics, meta + (RoadFortState.META_KEY to RoadFortState.toMetaValue(listOf(fort))))
        assertIs<TravelAssessment.Eligible>(friendly[0])
    }

    @Test fun `batch returns identical tie break paths and isolates overflowing destinations`() {
        val pin = "a".repeat(64)
        fun node(id: String) = StrategicNodeRef.LandProvince(id)
        fun edge(id: String, from: String, to: String) = TraversalEdge(id, node(from), node(to),
            TraversalMode.LAND, false, 1, 7, RiskBand.LOW, SeasonalAvailability.ALWAYS,
            sourceRefs = listOf("qa"), confidence = EvidenceConfidence.REVIEWED)
        val graph = StrategicTopologySnapshot("batch", setOf("A", "B", "C", "D", "E"), emptyList(),
            listOf(edge("a", "A", "B"), edge("z", "B", "D"), edge("b", "A", "C"),
                edge("c", "C", "D")), emptyList(), mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
        val costs = LandMarchMetricSnapshot(graph, pin,
            graph.traversalEdges.map { LandMarchEdgeMetric(it.id, 20_000_000, 30_000_000) })
        val graphMeta = meta + (LandPassageState.META_KEY to LandPassageState.initialMetaValue(graph))
        val queries = listOf("D", "B", "E", "A", "D").map { request.copy(inputId = TravelInput.FORCED_MARCH, destination = node(it)) to node(it) }
        val results = TravelRules.assessMany(queries, snapshot, graph, costs, graphMeta)
        assertSameAssessments(queries.map { (query, target) ->
            TravelRules.assess(query, target, snapshot, graph, costs, graphMeta) }, results)
        val path = assertIs<TravelAssessment.Eligible>(results[0]).path
        assertEquals(listOf("a", "z"), path.edgeIds)
        assertEquals(60_000_000L, path.totalCostMm)
        assertEquals(2L, MarchDestinationEstimate.of(path, costs, LandMarchMetricSnapshot.NORMAL_BUDGET_MM).estimatedTurns)
        assertEquals(TravelAssessment.Rejected(TravelFailure.NO_ROUTE), results[2])
        assertEquals(TravelAssessment.Rejected(TravelFailure.ALREADY_THERE), results[3])
        assertSameAssessments(listOf(results[0]), listOf(results[4]))
        assertSameAssessments(results.reversed(), TravelRules.assessMany(queries.reversed(), snapshot, graph, costs, graphMeta))
        val overflow = StrategicTopologySnapshot("overflow", setOf("A", "B", "C"), emptyList(),
            listOf(edge("ab", "A", "B"), edge("bc", "B", "C")), emptyList(),
            mapOf(LandMarchMetricSnapshot.TILES_PATH to pin))
        val huge = Long.MAX_VALUE / 2 + 1
        val hugeCosts = LandMarchMetricSnapshot(overflow, pin,
            listOf(LandMarchEdgeMetric("ab", 1, huge), LandMarchEdgeMetric("bc", 1, 1)))
        val overflowMeta = meta + (LandPassageState.META_KEY to LandPassageState.initialMetaValue(overflow))
        val overflowQueries = listOf("C", "B", "A", "C", "B").map { request.copy(inputId = TravelInput.RETURN, destination = null) to node(it) }
        val overflowResults = TravelRules.assessMany(overflowQueries, snapshot, overflow, hugeCosts, overflowMeta)
        assertEquals(TravelAssessment.Rejected(TravelFailure.NO_ROUTE), overflowResults[0])
        assertEquals(huge, assertIs<TravelAssessment.Eligible>(overflowResults[1]).path.totalCostMm)
        assertEquals(TravelAssessment.Rejected(TravelFailure.ALREADY_THERE), overflowResults[2])
        assertEquals(overflowResults[0], overflowResults[3])
        assertSameAssessments(listOf(overflowResults[1]), listOf(overflowResults[4]))
        assertSameAssessments(overflowQueries.map { (query, target) ->
            TravelRules.assess(query, target, snapshot, overflow, hugeCosts, overflowMeta) }, overflowResults)
    }
}
