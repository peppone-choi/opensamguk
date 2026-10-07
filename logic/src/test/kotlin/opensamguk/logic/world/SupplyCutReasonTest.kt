package opensamguk.logic.world

import kotlin.test.*

class SupplyCutReasonTest {
    private val cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1))
    private val capitals = listOf(SupplyCapital(1, 1))
    private val bindings = mapOf(1 to 0, 2 to 2)
    private fun topology() = StrategicTopologySnapshot("supply-test", setOf("a", "b", "c"), emptyList(),
        listOf("ab" to ("a" to "b"), "bc" to ("b" to "c")).map { (id, ends) ->
            TraversalEdge(id, StrategicNodeRef.LandProvince(ends.first), StrategicNodeRef.LandProvince(ends.second),
                TraversalMode.LAND, false, 1, 1, RiskBand.LOW, SeasonalAvailability.ALWAYS,
                true, listOf("test:source"), EvidenceConfidence.REVIEWED, initiallyOpen = false)
        }, emptyList(), mapOf("fixture" to "test"))
    private fun network(closed: String? = null, blocked: Set<String> = emptySet()): StrategicSupplyNetwork {
        val t = topology()
        val states = t.traversalEdges.associate { it.id to StrategicEdgeState(active = it.id != closed) }
        return StrategicSupplyNetwork(t, listOf("a", "b", "c"), null,
            mapOf(1 to StrategicEdgeStateSnapshot(t.topologyRevision, t.contentHash, states)), mapOf(1 to blocked))
    }
    private fun reasons(network: StrategicSupplyNetwork, owners: IntArray = intArrayOf(1, 1, 1),
        roots: List<SupplyCapital> = capitals): Map<Int, SupplyCutReason> {
        val supplied = network.suppliedCities(cities, roots, owners, bindings)
        return network.disconnectionReasons(cities, roots, owners, bindings, supplied)
    }

    @Test fun `live completed roads supply both cities without a cut reason`() {
        assertEquals(setOf(1, 2), network().suppliedCities(cities, capitals, intArrayOf(1, 1, 1), bindings))
        assertTrue(reasons(network()).isEmpty())
    }
    @Test fun `missing or captured capital is a proven source absence`() {
        assertEquals(mapOf(1 to SupplyCutReason.NO_SOURCE, 2 to SupplyCutReason.NO_SOURCE), reasons(network(), roots = emptyList()))
        assertEquals(SupplyCutReason.NO_SOURCE, reasons(network(), roots = listOf(SupplyCapital(3, 1)))[2])
    }
    @Test fun `enemy and neutral corridors are ownership cuts only if that constraint removal restores supply`() {
        for (owner in listOf(0, 2)) assertEquals(SupplyCutReason.OWNERSHIP_CUT, reasons(network(), intArrayOf(1, owner, 1))[2])
    }
    @Test fun `actual inactive passage and army blocker have different proven causes`() {
        assertEquals(SupplyCutReason.PASSAGE_CUT, reasons(network(closed = "bc"))[2])
        assertEquals(SupplyCutReason.MILITARY_CUT, reasons(network(blocked = setOf("b")))[2])
    }
    @Test fun `multiple independent constraints never become a guessed single cause`() {
        assertEquals(SupplyCutReason.UNKNOWN, reasons(network(closed = "bc"), intArrayOf(1, 2, 1))[2])
        assertEquals(SupplyCutReason.UNKNOWN, reasons(network(closed = "bc", blocked = setOf("b")))[2])
    }
    @Test fun `disconnected geometry remains unknown even with a visible city connection elsewhere`() {
        val t = StrategicTopologySnapshot("no-road", setOf("a", "b", "c"), emptyList(), emptyList(), emptyList(), mapOf("fixture" to "test"))
        assertEquals(SupplyCutReason.UNKNOWN, reasons(StrategicSupplyNetwork(t, listOf("a", "b", "c"), null))[2])
    }
}
