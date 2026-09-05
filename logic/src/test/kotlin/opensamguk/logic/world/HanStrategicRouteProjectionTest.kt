package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HanStrategicRouteProjectionTest {
    @Test
    fun `dry projection uses stable province IDs and excludes river only and adjudicated borders`() {
        val provinces = listOf("DIRECT-PARENT-0035-def", "45098", "45022")
        val owner = intArrayOf(1, 2, 0, 1, 2, 0)
        val terrain = listOf("113", "113")
        val edges = projectHanDryLandEdges(provinces, owner, terrain, setOf('1'), emptyList(), "sha")
        assertEquals(1, edges.size)
        assertEquals(setOf("land:45098", "land:45022"), setOf(edges.single().from.canonicalKey, edges.single().to.canonicalKey))
        assertEquals(true, edges.single().supplyAllowed)
        val barrier = RiverBarrier("river", "45098", "45022", listOf("review:river"), EvidenceConfidence.REVIEWED)
        assertEquals(emptyList(), projectHanDryLandEdges(provinces, owner, terrain, setOf('1'), listOf(barrier), "sha"))
        val reordered = projectHanDryLandEdges(
            listOf("45022", "DIRECT-PARENT-0035-def", "45098"),
            intArrayOf(2, 0, 1, 2, 0, 1), terrain, setOf('1'), emptyList(), "sha",
        )
        assertEquals(edges, reordered)
    }

    @Test
    fun `invalid owner domains and incomplete raster coverage fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            projectHanDryLandEdges(listOf("P1"), intArrayOf(1), listOf("1"), setOf('1'), emptyList(), "sha")
        }
        assertFailsWith<IllegalArgumentException> {
            projectHanDryLandEdges(listOf("P1"), intArrayOf(0), listOf("11"), setOf('1'), emptyList(), "sha")
        }
    }

    @Test
    fun `runtime identities resolve through an exact route binding and never treat missing provinces as ordinals`() {
        val edges = projectHanDryLandEdges(listOf("A", "B"), intArrayOf(0, 1), listOf("11"), setOf('1'), emptyList(), "sha")
        val topology = StrategicTopologySnapshot("test", setOf("A", "B"), emptyList(), edges, emptyList(), mapOf("base" to "sha"))
        val bindings = listOf(
            HanStrategicRouteBinding(273, "route-lu", "physical-lu", "A"),
            HanStrategicRouteBinding(781, "route-licheng", "physical-licheng", "B"),
            HanStrategicRouteBinding(5, "unmapped", "physical-unmapped", null),
        )
        val projection = HanStrategicRouteProjection(topology, bindings)
        val state = StrategicEdgeStateSnapshot(topology.topologyRevision, topology.contentHash, emptyMap())
        assertEquals(listOf("land:A", "land:B"), assertIs<StrategicPathResult.Resolved>(projection.resolve(273, 781, 1, state)).path.nodeKeys)
        assertEquals(PathDenialCode.UNKNOWN_NODE, assertIs<StrategicPathResult.Denied>(projection.resolve(5, 781, 1, state)).code)
        assertFailsWith<IllegalArgumentException> { HanStrategicRouteProjection(topology, bindings + bindings[0]) }
        assertFailsWith<IllegalArgumentException> {
            HanStrategicRouteProjection(topology, bindings + HanStrategicRouteBinding(8, "route-lu", "another", "A"))
        }
    }
}
