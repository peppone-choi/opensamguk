package opensamguk.logic.world

import opensamguk.common.constants.CityConst.RawCity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StrategicSupplyNetworkTest {
    private fun land(id: String) = StrategicNodeRef.LandProvince(id)
    private fun water(id: String) = StrategicNodeRef.WaterZone(id)
    private fun edge(
        id: String, from: StrategicNodeRef, to: StrategicNodeRef, mode: TraversalMode,
        supply: Boolean = true, season: SeasonalAvailability = SeasonalAvailability.ALWAYS,
    ) = TraversalEdge(id, from, to, mode, mode == TraversalMode.RIVER_DOWN, 1, 8,
        RiskBand.LOW, season, supply, listOf("test:reviewed"), EvidenceConfidence.REVIEWED)

    private fun topology(supply: Boolean = true, season: SeasonalAvailability = SeasonalAvailability.ALWAYS) =
        StrategicTopologySnapshot("test-v1", setOf("a", "b", "c"),
            listOf("upper", "lower").map {
                WaterZoneRecord(it, WaterZoneKind.RIVER_REACH, "test:$it", listOf("test:evidence"),
                    EvidenceConfidence.REVIEWED, seasonalAvailability = SeasonalAvailability.ALWAYS)
            }, listOf(
                edge("dry", land("a"), land("c"), TraversalMode.LAND),
                edge("embark", land("a"), water("upper"), TraversalMode.EMBARK),
                edge("down", water("upper"), water("lower"), TraversalMode.RIVER_DOWN, supply, season),
                edge("landing", water("lower"), land("b"), TraversalMode.DISEMBARK),
            ), emptyList(), mapOf("fixture" to "memory-only"))

    private fun control(t: StrategicTopologySnapshot, state: WaterBlockadeState = WaterBlockadeState.OPEN,
                        nation: Long = 1, contested: List<Long> = emptyList()) =
        WaterControlSnapshot.fromTopology(t, t.waterZones.map {
            WaterControlState(t.topologyRevision, t.contentHash, it.id, nation, contested, state, 1)
        })

    private fun live(t: StrategicTopologySnapshot, cap: Int? = 2, open: Boolean = true) =
        StrategicEdgeStateSnapshot(t.topologyRevision, t.contentHash,
            t.traversalEdges.filter { it.mode != TraversalMode.LAND }.associate {
                it.id to StrategicEdgeState(seasonOpen = open, availableCapacity = cap)
            })

    private fun supplied(t: StrategicTopologySnapshot, control: WaterControlSnapshot? = control(t),
                         state: StrategicEdgeStateSnapshot? = live(t), capital: Int = 1,
                         owners: IntArray = intArrayOf(1, 1, 1)): Set<Int> {
        val strategic = StrategicSupplyNetwork(t, listOf("a", "b", "c"), control,
            state?.let { mapOf(1 to it) }.orEmpty())
        return strategic.suppliedCities(listOf(SupplyCity(1, 1), SupplyCity(2, 1), SupplyCity(3, 1)),
            listOf(SupplyCapital(capital, 1)), owners, mapOf(1 to 0, 2 to 1, 3 to 2))
    }

    @Test fun `controlled explicit capacity opens reviewed directional supply but never reverse landing`() {
        val t = topology()
        assertEquals(setOf(1, 2, 3), supplied(t))
        assertEquals(setOf(2), supplied(t, capital = 2))
    }

    @Test fun `unknown control or capacity cannot grant water supply and dry land still works`() {
        val t = topology()
        assertEquals(setOf(1, 3), supplied(t, control = null))
        assertEquals(setOf(1, 3), supplied(t, control = WaterControlSnapshot.fromTopology(t)))
        assertEquals(setOf(1, 3), supplied(t, state = null))
        assertEquals(setOf(1, 3), supplied(t, state = live(t, cap = null)))
        assertEquals(setOf(1, 3), supplied(t, state = live(t, cap = 0)))
    }

    @Test fun `blockade contests enemy control and closed seasons remove only water extension`() {
        val t = topology()
        assertEquals(setOf(1, 3), supplied(t, control = control(t, WaterBlockadeState.BLOCKED)))
        assertEquals(setOf(1, 3), supplied(t, control = control(t, WaterBlockadeState.CONTESTED, contested = listOf(2))))
        assertEquals(setOf(1, 3), supplied(t, control = control(t, nation = 2)))
        val seasonal = topology(season = SeasonalAvailability.SEASONAL)
        assertEquals(setOf(1, 3), supplied(seasonal, state = live(seasonal, open = false)))
        assertEquals(setOf(1, 3), supplied(topology(season = SeasonalAvailability.CLOSED)))
        assertEquals(setOf(1, 3), supplied(topology(supply = false)))
    }

    @Test fun `water route cannot traverse enemy or neutral land`() {
        val t = topology()
        assertEquals(setOf(1), supplied(t, owners = intArrayOf(1, 2, 0)))
    }

    @Test fun `barrier diagnostics never become executable supply links`() {
        val base = topology()
        val t = StrategicTopologySnapshot(base.topologyRevision, base.landProvinceIds, base.waterZones,
            base.traversalEdges, listOf(RiverBarrier("river", "a", "c", listOf("test:review"), EvidenceConfidence.REVIEWED)),
            base.artifactHashes)
        assertEquals(setOf(1), supplied(t, control = null))
    }

    @Test fun `matching revision does not permit a different control zone inventory`() {
        val t = topology()
        assertFailsWith<IllegalArgumentException> {
            StrategicSupplyNetwork(t, listOf("a", "b", "c"),
                WaterControlSnapshot(t.topologyRevision, t.contentHash, setOf("invented")))
        }
    }

    @Test fun `stale pins unknown edges and province aliases fail closed`() {
        val t = topology()
        assertFailsWith<IllegalArgumentException> {
            StrategicSupplyNetwork(t, listOf("a", "b", "c"), control(topology(supply = false)))
        }
        assertFailsWith<IllegalArgumentException> {
            supplied(t, state = StrategicEdgeStateSnapshot("stale", t.contentHash, emptyMap()))
        }
        assertFailsWith<IllegalArgumentException> {
            supplied(t, state = StrategicEdgeStateSnapshot(t.topologyRevision, t.contentHash,
                mapOf("unknown" to StrategicEdgeState())))
        }
        assertFailsWith<IllegalArgumentException> { StrategicSupplyNetwork(t, listOf("a", "a", "c"), control(t)) }
    }

    @Test fun `blocked water still passes through the existing dual evidence safety gate`() {
        val t = topology()
        val network = SpatialSupplyNetwork(intArrayOf(1, 1, 1), listOf(intArrayOf(2), intArrayOf(), intArrayOf(0)),
            mapOf(1 to 0, 2 to 1, 3 to 2), strategicSupply = StrategicSupplyNetwork(t, listOf("a", "b", "c"),
                control(t, WaterBlockadeState.BLOCKED), mapOf(1 to live(t))))
        val cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1), SupplyCity(3, 1))
        val cityConst = InitCityOverrideVariant("supply-fixture", listOf(
            RawCity(1, "A", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B")),
            RawCity(2, "B", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("A")),
            RawCity(3, "C", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, emptyList()),
        ))
        val evaluation = evaluateSupplyReachability(cities, listOf(SupplyCapital(1, 1)), cityConst, network)
        val second = evaluation.rows.single { it.cityId == 2 }
        assertEquals(false, second.spatialGraphSupplied)
        assertTrue(second.cityGraphSupplied)
        assertEquals(SupplyReachabilityVerdict.CITY_ONLY_PROTECTED, second.verdict)
        assertTrue(2 in evaluation.suppliedCityIds)
    }
}
