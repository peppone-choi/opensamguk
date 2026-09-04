package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class StrategicPathResolverTest {

    @Test
    fun `dry land resolves but a reviewed river barrier requires an active crossing`() {
        val landEdge = edge("land-1-2", land(1), land(2), TraversalMode.LAND)
        val dry = topology(edges = listOf(landEdge))
        val dryPath = assertIs<StrategicPathResult.Resolved>(resolve(dry, land(1), land(2)))
        assertEquals(listOf("land-1-2"), dryPath.path.edgeIds)

        val barrier = RiverBarrier("river-1-2", 1, 2, listOf("review:river"), EvidenceConfidence.REVIEWED)
        val blocked = topology(edges = listOf(landEdge), barriers = listOf(barrier))
        assertEquals(
            PathDenialCode.RIVER_CROSSING_REQUIRED,
            assertIs<StrategicPathResult.Denied>(resolve(blocked, land(1), land(2))).code,
        )

        val ford = edge("ford-1-2", land(1), land(2), TraversalMode.FORD)
        val crossed = topology(edges = listOf(landEdge, ford), barriers = listOf(barrier))
        assertEquals(
            listOf("ford-1-2"),
            assertIs<StrategicPathResult.Resolved>(resolve(crossed, land(1), land(2))).path.edgeIds,
        )
    }

    @Test
    fun `water transport requires ordered embark water and disembark edges with capacity`() {
        val topology = topology(
            zones = listOf(zone("river-upper"), zone("river-lower")),
            edges = listOf(
                edge("01-embark", land(1), water("river-upper"), TraversalMode.EMBARK, capacity = 8),
                edge(
                    "02-downstream", water("river-upper"), water("river-lower"),
                    TraversalMode.RIVER_DOWN, cost = 2, capacity = 6, directed = true,
                ),
                edge("03-disembark", water("river-lower"), land(2), TraversalMode.DISEMBARK, capacity = 7),
            ),
        )

        val result = assertIs<StrategicPathResult.Resolved>(
            resolve(topology, land(1), land(2), requiredCapacity = 5),
        ).path
        assertEquals(listOf("land:1", "water:river-upper", "water:river-lower", "land:2"), result.nodeKeys)
        assertEquals(listOf("01-embark", "02-downstream", "03-disembark"), result.edgeIds)
        assertEquals(listOf(TraversalMode.EMBARK, TraversalMode.RIVER_DOWN, TraversalMode.DISEMBARK), result.modes)
        assertEquals(4L, result.totalCost)
        assertEquals(6, result.capacity)
        assertEquals(topology.topologyRevision, result.topologyRevision)
        assertEquals(topology.contentHash, result.topologyHash)

        assertEquals(
            PathDenialCode.NO_TRANSPORT_CAPACITY,
            assertIs<StrategicPathResult.Denied>(
                resolve(topology, land(1), land(2), requiredCapacity = 7),
            ).code,
        )
    }

    @Test
    fun `upstream and downstream costs remain direction-specific`() {
        val topology = topology(
            zones = listOf(zone("upper"), zone("lower")),
            edges = listOf(
                edge("down", water("upper"), water("lower"), TraversalMode.RIVER_DOWN, cost = 2, directed = true),
                edge("up", water("lower"), water("upper"), TraversalMode.RIVER_UP, cost = 5, directed = true),
            ),
        )

        assertEquals(2L, assertIs<StrategicPathResult.Resolved>(resolve(topology, water("upper"), water("lower"))).path.totalCost)
        assertEquals(5L, assertIs<StrategicPathResult.Resolved>(resolve(topology, water("lower"), water("upper"))).path.totalCost)
    }

    @Test
    fun `seasonal closure and blockade return a typed water denial`() {
        val seasonal = edge(
            "seasonal-lake", water("west"), water("east"), TraversalMode.LAKE,
            seasonal = SeasonalAvailability.SEASONAL,
        )
        val topology = topology(
            zones = listOf(zone("west", WaterZoneKind.LAKE_BASIN), zone("east", WaterZoneKind.LAKE_BASIN)),
            edges = listOf(seasonal),
        )

        val closedSeason = edgeStates(topology, mapOf("seasonal-lake" to StrategicEdgeState(seasonOpen = false)))
        assertEquals(
            PathDenialCode.WATERWAY_BLOCKED,
            assertIs<StrategicPathResult.Denied>(resolve(topology, water("west"), water("east"), states = closedSeason)).code,
        )

        val blockade = edgeStates(
            topology,
            mapOf("seasonal-lake" to StrategicEdgeState(seasonOpen = true, blockaded = true)),
        )
        assertEquals(
            PathDenialCode.WATERWAY_BLOCKED,
            assertIs<StrategicPathResult.Denied>(resolve(topology, water("west"), water("east"), states = blockade)).code,
        )

        val open = edgeStates(topology, mapOf("seasonal-lake" to StrategicEdgeState(seasonOpen = true)))
        assertIs<StrategicPathResult.Resolved>(resolve(topology, water("west"), water("east"), states = open))
    }

    @Test
    fun `resolver never invents a lake or coastal shortcut`() {
        val topology = topology(zones = listOf(zone("lake-a"), zone("lake-b")), edges = emptyList())

        assertEquals(
            PathDenialCode.NO_LAND_CONNECTION,
            assertIs<StrategicPathResult.Denied>(resolve(topology, water("lake-a"), water("lake-b"))).code,
        )
        assertEquals(
            PathDenialCode.NO_EMBARK_POINT,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), water("lake-a"))).code,
        )
    }

    @Test
    fun `equal-cost paths use the lexicographically stable edge-id sequence`() {
        val topology = topology(
            landIds = setOf(1, 2, 3, 4),
            edges = listOf(
                edge("b-first", land(1), land(2), TraversalMode.LAND),
                edge("b-last", land(2), land(4), TraversalMode.LAND),
                edge("a-first", land(1), land(3), TraversalMode.LAND),
                edge("z-last", land(3), land(4), TraversalMode.LAND),
            ),
        )

        val first = assertIs<StrategicPathResult.Resolved>(resolve(topology, land(1), land(4))).path
        val shuffled = topology(
            landIds = setOf(4, 3, 2, 1),
            edges = topology.traversalEdges.reversed(),
        )
        val second = assertIs<StrategicPathResult.Resolved>(resolve(shuffled, land(1), land(4))).path

        assertEquals(listOf("a-first", "z-last"), first.edgeIds)
        assertEquals(first.edgeIds, second.edgeIds)
        assertEquals(first.pathHash, second.pathHash)
    }

    @Test
    fun `tie breaking compares edge ids rather than their encoded lengths`() {
        val topology = topology(
            landIds = setOf(1, 2, 3, 4),
            edges = listOf(
                edge("b", land(1), land(2), TraversalMode.LAND),
                edge("a-tail", land(2), land(4), TraversalMode.LAND),
                edge("aa", land(1), land(3), TraversalMode.LAND),
                edge("z-tail", land(3), land(4), TraversalMode.LAND),
            ),
        )

        assertEquals(
            listOf("aa", "z-tail"),
            assertIs<StrategicPathResult.Resolved>(resolve(topology, land(1), land(4))).path.edgeIds,
        )
    }

    @Test
    fun `stale topology state is rejected before path search`() {
        val topology = topology(edges = listOf(edge("land", land(1), land(2), TraversalMode.LAND)))
        val staleRevision = StrategicEdgeStateSnapshot(
            topologyRevision = "old",
            topologyHash = topology.contentHash,
            edgeStates = emptyMap(),
        )
        val staleHash = StrategicEdgeStateSnapshot(
            topologyRevision = topology.topologyRevision,
            topologyHash = "old-hash",
            edgeStates = emptyMap(),
        )

        assertEquals(
            PathDenialCode.TOPOLOGY_REVISION_STALE,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), land(2), states = staleRevision)).code,
        )
        assertEquals(
            PathDenialCode.TOPOLOGY_REVISION_STALE,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), land(2), states = staleHash)).code,
        )
    }

    @Test
    fun `state snapshot is immutable and unknown edge state fails closed`() {
        val topology = topology(edges = listOf(edge("land", land(1), land(2), TraversalMode.LAND)))
        val values = mutableMapOf("land" to StrategicEdgeState(active = true))
        val state = edgeStates(topology, values)
        values["land"] = StrategicEdgeState(active = false)

        assertIs<StrategicPathResult.Resolved>(resolve(topology, land(1), land(2), states = state))
        assertFailsWith<UnsupportedOperationException> { (state.edgeStates as MutableMap).clear() }
        val unknown = edgeStates(topology, mapOf("not-in-topology" to StrategicEdgeState()))
        assertEquals(
            PathDenialCode.TOPOLOGY_STATE_INVALID,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), land(2), states = unknown)).code,
        )
    }

    @Test
    fun `unknown nodes fail closed even when origin equals destination`() {
        val topology = topology(edges = emptyList())

        assertEquals(
            PathDenialCode.UNKNOWN_NODE,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(999), land(999))).code,
        )
        assertEquals(
            PathDenialCode.UNKNOWN_NODE,
            assertIs<StrategicPathResult.Denied>(resolve(topology, water("missing"), water("missing"))).code,
        )
    }

    @Test
    fun `unrelated low-capacity dead end does not hide a river crossing denial`() {
        val topology = topology(
            landIds = setOf(1, 2, 3),
            edges = listOf(
                edge("barred-road", land(1), land(2), TraversalMode.LAND, capacity = 10),
                edge("irrelevant-low-capacity", land(1), land(3), TraversalMode.LAND, capacity = 1),
            ),
            barriers = listOf(RiverBarrier("river", 1, 2, listOf("review:river"), EvidenceConfidence.REVIEWED)),
        )

        assertEquals(
            PathDenialCode.RIVER_CROSSING_REQUIRED,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), land(2), requiredCapacity = 2)).code,
        )
    }

    @Test
    fun `only an embark point reachable from the origin prevents no-embark denial`() {
        val topology = topology(
            landIds = setOf(1, 2),
            zones = listOf(zone("lake", WaterZoneKind.LAKE_BASIN)),
            edges = listOf(edge("unreachable-port", land(2), water("lake"), TraversalMode.EMBARK)),
        )

        assertEquals(
            PathDenialCode.NO_EMBARK_POINT,
            assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), water("lake"))).code,
        )
    }

    @Test
    fun `inactive crossings and statically closed water routes stay unavailable`() {
        val barrier = RiverBarrier("river", 1, 2, listOf("review:river"), EvidenceConfidence.REVIEWED)
        listOf(TraversalMode.FORD, TraversalMode.BRIDGE, TraversalMode.FERRY).forEach { mode ->
            val topology = topology(
                edges = listOf(
                    edge("road", land(1), land(2), TraversalMode.LAND),
                    edge("crossing", land(1), land(2), mode),
                ),
                barriers = listOf(barrier),
            )
            val state = edgeStates(topology, mapOf("crossing" to StrategicEdgeState(active = false)))
            assertEquals(
                PathDenialCode.RIVER_CROSSING_REQUIRED,
                assertIs<StrategicPathResult.Denied>(resolve(topology, land(1), land(2), states = state)).code,
            )
        }

        val lake = topology(
            zones = listOf(zone("west", WaterZoneKind.LAKE_BASIN), zone("east", WaterZoneKind.LAKE_BASIN)),
            edges = listOf(
                edge(
                    "closed-lake", water("west"), water("east"), TraversalMode.LAKE,
                    seasonal = SeasonalAvailability.CLOSED,
                ),
            ),
        )
        assertEquals(
            PathDenialCode.WATERWAY_BLOCKED,
            assertIs<StrategicPathResult.Denied>(resolve(lake, water("west"), water("east"))).code,
        )
    }

    @Test
    fun `path hash changes when an ordered edge changes`() {
        val direct = topology(edges = listOf(edge("direct", land(1), land(2), TraversalMode.LAND)))
        val via = topology(
            landIds = setOf(1, 2, 3),
            edges = listOf(
                edge("via-a", land(1), land(3), TraversalMode.LAND),
                edge("via-b", land(3), land(2), TraversalMode.LAND),
            ),
        )

        val directHash = assertIs<StrategicPathResult.Resolved>(resolve(direct, land(1), land(2))).path.pathHash
        val viaHash = assertIs<StrategicPathResult.Resolved>(resolve(via, land(1), land(2))).path.pathHash
        assertNotEquals(directHash, viaHash)
    }

    private fun resolve(
        topology: StrategicTopologySnapshot,
        from: StrategicNodeRef,
        to: StrategicNodeRef,
        requiredCapacity: Int = 1,
        states: StrategicEdgeStateSnapshot = edgeStates(topology),
    ): StrategicPathResult = StrategicPathResolver.resolve(
        topology,
        StrategicPathRequest(from, to, requiredCapacity),
        states,
    )

    private fun edgeStates(
        topology: StrategicTopologySnapshot,
        values: Map<String, StrategicEdgeState> = emptyMap(),
    ) = StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, values)

    private fun topology(
        landIds: Set<Int> = setOf(1, 2),
        zones: List<WaterZoneRecord> = emptyList(),
        edges: List<TraversalEdge>,
        barriers: List<RiverBarrier> = emptyList(),
    ) = StrategicTopologySnapshot(
        topologyRevision = "test-v1",
        landProvinceIds = landIds,
        waterZones = zones,
        traversalEdges = edges,
        riverBarriers = barriers,
        artifactHashes = mapOf("fixture" to "sha256"),
    )

    private fun zone(id: String, kind: WaterZoneKind = WaterZoneKind.RIVER_REACH) = WaterZoneRecord(
        id = id,
        kind = kind,
        geometryRef = "geometry:$id",
        sourceRefs = listOf("source:$id"),
        confidence = EvidenceConfidence.REVIEWED,
        seasonalAvailability = SeasonalAvailability.ALWAYS,
    )

    private fun edge(
        id: String,
        from: StrategicNodeRef,
        to: StrategicNodeRef,
        mode: TraversalMode,
        cost: Int = 1,
        capacity: Int = 10,
        directed: Boolean = false,
        seasonal: SeasonalAvailability = SeasonalAvailability.ALWAYS,
    ) = TraversalEdge(
        id = id,
        from = from,
        to = to,
        mode = mode,
        directed = directed,
        movementCost = cost,
        capacity = capacity,
        riskBand = RiskBand.LOW,
        seasonalAvailability = seasonal,
        sourceRefs = listOf("source:$id"),
        confidence = EvidenceConfidence.REVIEWED,
    )

    private fun land(id: Int) = StrategicNodeRef.LandProvince(id)
    private fun water(id: String) = StrategicNodeRef.WaterZone(id)
}
