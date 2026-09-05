package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class StrategicTopologyTest {

    @Test
    fun `canonical direct province identity remains a string instead of an array ordinal`() {
        val province = StrategicNodeRef.LandProvince("DIRECT-PARENT-0035-abcdef")
        val topology = StrategicTopologySnapshot(
            "stable-land", setOf("DIRECT-PARENT-0035-abcdef"), emptyList(), emptyList(), emptyList(),
            mapOf("base" to "sha"),
        )
        assertEquals("land:DIRECT-PARENT-0035-abcdef", province.canonicalKey)
        assertEquals(true, topology.containsNode(province))
    }

    @Test
    fun `land and water nodes keep separate stable namespaces`() {
        val land = StrategicNodeRef.LandProvince("7")
        val water = StrategicNodeRef.WaterZone("river-yellow-lower")

        assertEquals("land:7", land.canonicalKey)
        assertEquals("water:river-yellow-lower", water.canonicalKey)
        assertFalse(land.canonicalKey == water.canonicalKey)
        assertEquals(
            listOf("RIVER_REACH", "LAKE_BASIN", "COASTAL_SEA"),
            WaterZoneKind.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "LAND", "FORD", "BRIDGE", "FERRY", "EMBARK", "DISEMBARK",
                "RIVER_UP", "RIVER_DOWN", "LAKE", "COASTAL",
            ),
            TraversalMode.entries.map { it.name },
        )
        assertEquals(listOf("ALWAYS", "SEASONAL", "CLOSED"), SeasonalAvailability.entries.map { it.name })
    }

    @Test
    fun `water traversal never supplies unless the reviewed edge opts in`() {
        val edge = edge(
            id = "embark-a",
            from = StrategicNodeRef.LandProvince("1"),
            to = StrategicNodeRef.WaterZone("lake-a"),
            mode = TraversalMode.EMBARK,
        )

        assertFalse(edge.supplyAllowed)
    }

    @Test
    fun `snapshot rejects duplicate dangling self and canonical duplicate edges`() {
        val zone = waterZone("lake-a")
        val land = setOf(1, 2)
        val valid = edge("land-1-2", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.LandProvince("2"))

        assertFailsWith<IllegalArgumentException> {
            snapshot(land, listOf(zone, zone), listOf(valid))
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(land, listOf(zone), listOf(valid, valid.copy()))
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                land,
                listOf(zone),
                listOf(edge("self", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.LandProvince("1"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                land,
                listOf(zone),
                listOf(edge("dangling", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.LandProvince("99"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                land,
                listOf(zone),
                listOf(
                    valid,
                    edge("same-undirected", StrategicNodeRef.LandProvince("2"), StrategicNodeRef.LandProvince("1")),
                ),
            )
        }
    }

    @Test
    fun `only evidence-reviewed river flow edges may be directed`() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                setOf(1, 2),
                emptyList(),
                listOf(
                    edge(
                        id = "one-way-road",
                        from = StrategicNodeRef.LandProvince("1"),
                        to = StrategicNodeRef.LandProvince("2"),
                        directed = true,
                    ),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                emptySet(),
                listOf(waterZone("upper", WaterZoneKind.RIVER_REACH), waterZone("lower", WaterZoneKind.RIVER_REACH)),
                listOf(
                    edge(
                        id = "inferred-flow",
                        from = StrategicNodeRef.WaterZone("upper"),
                        to = StrategicNodeRef.WaterZone("lower"),
                        mode = TraversalMode.RIVER_DOWN,
                        directed = true,
                        confidence = EvidenceConfidence.INFERRED,
                    ),
                ),
            )
        }

        snapshot(
            emptySet(),
            listOf(waterZone("upper", WaterZoneKind.RIVER_REACH), waterZone("lower", WaterZoneKind.RIVER_REACH)),
            listOf(
                edge(
                    id = "reviewed-flow",
                    from = StrategicNodeRef.WaterZone("upper"),
                    to = StrategicNodeRef.WaterZone("lower"),
                    mode = TraversalMode.RIVER_DOWN,
                    directed = true,
                ),
            ),
        )
    }

    @Test
    fun `unreviewed crossings and waterways never enter executable topology`() {
        val executableModes = TraversalMode.entries.filterNot { it == TraversalMode.LAND }

        executableModes.forEach { mode ->
            val zones = when (mode) {
                TraversalMode.EMBARK, TraversalMode.DISEMBARK -> listOf(waterZone("water"))
                TraversalMode.RIVER_UP, TraversalMode.RIVER_DOWN ->
                    listOf(waterZone("upper", WaterZoneKind.RIVER_REACH), waterZone("lower", WaterZoneKind.RIVER_REACH))
                TraversalMode.LAKE ->
                    listOf(waterZone("west", WaterZoneKind.LAKE_BASIN), waterZone("east", WaterZoneKind.LAKE_BASIN))
                TraversalMode.COASTAL ->
                    listOf(waterZone("north", WaterZoneKind.COASTAL_SEA), waterZone("south", WaterZoneKind.COASTAL_SEA))
                else -> emptyList()
            }
            val (from, to) = when (mode) {
                TraversalMode.EMBARK -> StrategicNodeRef.LandProvince("1") to StrategicNodeRef.WaterZone("water")
                TraversalMode.DISEMBARK -> StrategicNodeRef.WaterZone("water") to StrategicNodeRef.LandProvince("1")
                TraversalMode.RIVER_UP, TraversalMode.RIVER_DOWN ->
                    StrategicNodeRef.WaterZone("upper") to StrategicNodeRef.WaterZone("lower")
                TraversalMode.LAKE -> StrategicNodeRef.WaterZone("west") to StrategicNodeRef.WaterZone("east")
                TraversalMode.COASTAL -> StrategicNodeRef.WaterZone("north") to StrategicNodeRef.WaterZone("south")
                else -> StrategicNodeRef.LandProvince("1") to StrategicNodeRef.LandProvince("2")
            }
            assertFailsWith<IllegalArgumentException>("$mode must be reviewed before execution") {
                snapshot(
                    setOf(1, 2),
                    zones,
                    listOf(
                        edge(
                            id = "inferred-${mode.name.lowercase()}",
                            from = from,
                            to = to,
                            mode = mode,
                            directed = mode == TraversalMode.RIVER_UP || mode == TraversalMode.RIVER_DOWN,
                            confidence = EvidenceConfidence.INFERRED,
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun `river flow modes must declare their direction`() {
        assertFailsWith<IllegalArgumentException> {
            snapshot(
                emptySet(),
                listOf(waterZone("upper", WaterZoneKind.RIVER_REACH), waterZone("lower", WaterZoneKind.RIVER_REACH)),
                listOf(
                    edge(
                        "flow-without-direction",
                        StrategicNodeRef.WaterZone("upper"),
                        StrategicNodeRef.WaterZone("lower"),
                        TraversalMode.RIVER_DOWN,
                        directed = false,
                    ),
                ),
            )
        }
    }

    @Test
    fun `canonical hash input is stable under shuffled source collections`() {
        val zones = listOf(waterZone("lake-b"), waterZone("lake-a"))
        val edges = listOf(
            edge("z-edge", StrategicNodeRef.LandProvince("2"), StrategicNodeRef.WaterZone("lake-b"), TraversalMode.EMBARK),
            edge("a-edge", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.WaterZone("lake-a"), TraversalMode.EMBARK),
        )
        val first = snapshot(setOf(2, 1), zones, edges, mapOf("tiles" to "bbb", "ledger" to "aaa"))
        val second = snapshot(setOf(1, 2), zones.reversed(), edges.reversed(), mapOf("ledger" to "aaa", "tiles" to "bbb"))

        assertEquals(first.canonicalHashInput(), second.canonicalHashInput())
        assertEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun `snapshot stays immutable when caller collections are mutated`() {
        val land = mutableSetOf(1)
        val zones = mutableListOf(waterZone("lake-a"))
        val edges = mutableListOf(
            edge("embark", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.WaterZone("lake-a"), TraversalMode.EMBARK),
        )
        val hashes = mutableMapOf("tiles" to "hash")
        val topology = snapshot(land, zones, edges, hashes)
        val canonical = topology.canonicalHashInput()

        land += 99
        zones.clear()
        edges.clear()
        hashes["tiles"] = "changed"

        assertEquals(setOf("1"), topology.landProvinceIds)
        assertEquals(listOf("lake-a"), topology.waterZones.map(WaterZoneRecord::id))
        assertEquals(listOf("embark"), topology.traversalEdges.map(TraversalEdge::id))
        assertEquals(mapOf("tiles" to "hash"), topology.artifactHashes)
        assertEquals(canonical, topology.canonicalHashInput())
        assertEquals(64, topology.contentHash.length)
    }

    @Test
    fun `snapshot collections cannot be mutated through a runtime mutable cast`() {
        val topology = StrategicTopologySnapshot(
            topologyRevision = "immutable-v1",
            landProvinceIds = setOf("1", "2"),
            waterZones = listOf(waterZone("lake-a")),
            traversalEdges = listOf(
                edge("embark", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.WaterZone("lake-a"), TraversalMode.EMBARK),
            ),
            riverBarriers = listOf(
                RiverBarrier("river", "1", "2", listOf("source:river"), EvidenceConfidence.REVIEWED),
            ),
            artifactHashes = mapOf("tiles" to "hash", "ledger" to "hash2"),
        )

        assertFailsWith<UnsupportedOperationException> { (topology.landProvinceIds as MutableSet).add("99") }
        assertFailsWith<UnsupportedOperationException> { (topology.waterZones as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (topology.waterZones[0].sourceRefs as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (topology.traversalEdges as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (topology.riverBarriers as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (topology.artifactHashes as MutableMap).clear() }
    }

    @Test
    fun `canonical encoding normalizes undirected endpoints and escapes collection boundaries`() {
        val forward = snapshot(
            setOf(1, 2),
            emptyList(),
            listOf(edge("road", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.LandProvince("2"))),
        )
        val reverse = snapshot(
            setOf(1, 2),
            emptyList(),
            listOf(edge("road", StrategicNodeRef.LandProvince("2"), StrategicNodeRef.LandProvince("1"))),
        )
        val oneSource = StrategicTopologySnapshot(
            topologyRevision = "han-water-v1",
            landProvinceIds = setOf("1", "2"),
            waterZones = emptyList(),
            traversalEdges = listOf(
                edge("road", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.LandProvince("2"))
                    .copy(sourceRefs = listOf("a,b")),
            ),
            riverBarriers = emptyList(),
            artifactHashes = mapOf("tiles" to "hash"),
        )
        val twoSources = StrategicTopologySnapshot(
            topologyRevision = "han-water-v1",
            landProvinceIds = setOf("1", "2"),
            waterZones = emptyList(),
            traversalEdges = listOf(
                edge("road", StrategicNodeRef.LandProvince("1"), StrategicNodeRef.LandProvince("2"))
                    .copy(sourceRefs = listOf("a", "b")),
            ),
            riverBarriers = emptyList(),
            artifactHashes = mapOf("tiles" to "hash"),
        )

        assertEquals(forward.contentHash, reverse.contentHash)
        assertFalse(oneSource.contentHash == twoSources.contentHash)
    }

    private fun waterZone(id: String, kind: WaterZoneKind = WaterZoneKind.LAKE_BASIN) = WaterZoneRecord(
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
        mode: TraversalMode = TraversalMode.LAND,
        directed: Boolean = false,
        confidence: EvidenceConfidence = EvidenceConfidence.REVIEWED,
    ) = TraversalEdge(
        id = id,
        from = from,
        to = to,
        mode = mode,
        directed = directed,
        movementCost = 1,
        capacity = 10,
        riskBand = RiskBand.LOW,
        seasonalAvailability = SeasonalAvailability.ALWAYS,
        sourceRefs = listOf("source:$id"),
        confidence = confidence,
    )

    private fun snapshot(
        land: Set<Int>,
        zones: List<WaterZoneRecord>,
        edges: List<TraversalEdge>,
        hashes: Map<String, String> = mapOf("tiles" to "hash"),
    ) = StrategicTopologySnapshot(
        topologyRevision = "han-water-v1",
        landProvinceIds = land.map(Int::toString).toSet(),
        waterZones = zones,
        traversalEdges = edges,
        riverBarriers = emptyList(),
        artifactHashes = hashes,
    )
}
