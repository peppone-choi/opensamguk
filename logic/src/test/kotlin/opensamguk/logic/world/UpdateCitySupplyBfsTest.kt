package opensamguk.logic.world

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.CityConst.RawCity
import opensamguk.common.constants.CityInitialDetail
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AREA A2 / Task SU1 — multi-source FIFO BFS over the active CityConst.path.
 *
 * Port target: PHP Event/Action/UpdateCitySupply.php:13-58 + CityConstBase.php:181-210 (path order).
 * DETERMINISTIC, NON-randomized.
 *
 *   cities[id] = {id, nation, supply=false}  ← SELECT city,nation FROM city WHERE nation!=0  (PK asc)
 *   seed queue from `SELECT capital,nation FROM nation WHERE level>0` (nation-id asc): skip if the
 *     capital is absent (not owned) or its nation mismatches (captured); else supply=true + enqueue.
 *   PROPAGATE FIFO (SplQueue == ArrayDeque addLast/removeFirst, BFS not DFS): dequeue, neighbors via
 *     CityConst::byID(id)->path keys (insertion order, NOT sorted); skip neighbor if not owned /
 *     nation-mismatch (supply does not cross borders) / already supplied; else supply=true + enqueue.
 */
class UpdateCitySupplyBfsTest {

    private fun spatialNetwork(
        owners: List<Int>,
        edges: List<Pair<Int, Int>>,
        cityProvinces: Map<Int, Int>,
    ): SpatialSupplyNetwork {
        val adjacency = List(owners.size) { mutableListOf<Int>() }
        for ((a, b) in edges) {
            adjacency[a] += b
            adjacency[b] += a
        }
        return SpatialSupplyNetwork(
            provinceOwners = owners.toIntArray(),
            provinceAdjacency = adjacency.map { it.toIntArray() },
            cityProvinceIndices = cityProvinces,
        )
    }

    // A tiny synthetic 5-city line variant: 1—2—3—4—5 (symmetric adjacency, insertion-ordered).
    private fun lineConst(): CityConstVariant {
        val rows = listOf(
            RawCity(1, "A", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B")),
            RawCity(2, "B", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("A", "C")),
            RawCity(3, "C", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B", "D")),
            RawCity(4, "D", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("C", "E")),
            RawCity(5, "E", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("D")),
        )
        return InitCityOverrideVariant("line5", rows)
    }

    private fun verdictConst(): CityConstVariant {
        val rows = listOf(
            RawCity(1, "A", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B", "C")),
            RawCity(2, "B", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("A")),
            RawCity(3, "C", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("A")),
            RawCity(4, "D", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, emptyList()),
            RawCity(5, "E", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, emptyList()),
        )
        return InitCityOverrideVariant("verdict5", rows)
    }

    @Test
    fun `a 3-city chain from one capital - all supplied`() {
        // nation 1 owns cities 1,2,3 (a chain); capital = city 1, level>0.
        val supplied = computeSuppliedCities(
            cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1), SupplyCity(3, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
        )
        assertEquals(setOf(1, 2, 3), supplied)
    }

    @Test
    fun `a border-blocked neighbor is not supplied (nation mismatch)`() {
        // nation 1 owns 1,2; nation 2 owns 3. Supply must NOT cross the 2—3 border.
        val supplied = computeSuppliedCities(
            cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1), SupplyCity(3, 2)),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(3, 2)),
            cityConst = lineConst(),
        )
        // nation 1: 1,2 supplied. nation 2: capital 3 supplied (4,5 not owned).
        assertEquals(setOf(1, 2, 3), supplied)
        // city 3 reached ONLY via its own nation-2 capital, never propagated from nation-1's city 2.
    }

    @Test
    fun `a level-0 wandering nation capital seeds nothing`() {
        // nation 1 (level>0) owns city 1; nation 9 is wandering (level 0) and "owns" city 5.
        // The level>0 guard is enforced by the caller building `capitals` only from level>0 nations,
        // so wandering nation 9 is absent from `capitals` → city 5 unsupplied.
        val supplied = computeSuppliedCities(
            cities = listOf(SupplyCity(1, 1), SupplyCity(5, 9)),
            capitals = listOf(SupplyCapital(1, 1)), // nation 9 (level 0) NOT seeded
            cityConst = lineConst(),
        )
        assertEquals(setOf(1), supplied)
        assertFalse(5 in supplied)
    }

    @Test
    fun `a captured capital (nation mismatch) seeds nothing`() {
        // nation 1's recorded capital is city 1, but city 1 is now owned by nation 2 (captured).
        val supplied = computeSuppliedCities(
            cities = listOf(SupplyCity(1, 2), SupplyCity(2, 2)),
            capitals = listOf(SupplyCapital(1, 1)), // nation 1 claims capital 1, but city 1.nation=2
            cityConst = lineConst(),
        )
        // capital mismatch → nation 1 seeds nothing; nation 2 has no capital row → nothing supplied.
        assertTrue(supplied.isEmpty())
    }

    @Test
    fun `an absent capital (not owned) seeds nothing`() {
        // nation 1's capital city 4 is not in the owned-cities set (lost) → skip.
        val supplied = computeSuppliedCities(
            cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1)),
            capitals = listOf(SupplyCapital(4, 1)),
            cityConst = lineConst(),
        )
        assertTrue(supplied.isEmpty())
    }

    @Test
    fun `neighbor visit order equals path key insertion order (BFS dequeue sequence, FIFO not DFS)`() {
        // A star + chain to expose FIFO vs DFS: capital C(3) has path [B(2), D(4)] (insertion order).
        // B has path [A(1), C], D has path [C, E(5)]. nation 1 owns all 5.
        // FIFO from capital 3: enqueue order = 3, then neighbors 2,4 (path order), then 2's new
        // neighbor 1, then 4's new neighbor 5  → discovery order [3,2,4,1,5].
        // DFS would give [3,2,1,4,5]. The discovery (insertion) order distinguishes them.
        val supplied = computeSuppliedCitiesOrdered(
            cities = listOf(
                SupplyCity(1, 1), SupplyCity(2, 1), SupplyCity(3, 1),
                SupplyCity(4, 1), SupplyCity(5, 1),
            ),
            capitals = listOf(SupplyCapital(3, 1)),
            cityConst = lineConst(),
        )
        assertContentEquals(listOf(3, 2, 4, 1, 5), supplied)
    }

    @Test
    fun `capitals are seeded in ascending nation id (multi-source - all capitals enqueued before propagation)`() {
        // Two nations, two disjoint single-city domains. Both capitals supplied; iteration order
        // is ascending nation id (anchor #2). City-map iteration is ascending city id (anchor #1).
        val supplied = computeSuppliedCitiesOrdered(
            cities = listOf(SupplyCity(1, 2), SupplyCity(5, 1)),
            capitals = listOf(SupplyCapital(5, 1), SupplyCapital(1, 2)),
            cityConst = lineConst(),
        )
        // Capitals seeded ascending nation id: nation 1 (capital 5) before nation 2 (capital 1).
        assertContentEquals(listOf(5, 1), supplied)
    }

    @Test
    fun `the canonical che base resolves real path adjacency`() {
        // Sanity: the real CityConst 'che' base — city 1 (업) path includes 남피(9). nation 1 owns
        // both → both supplied via the real adjacency.
        val che: CityConstVariant = CheCityConst
        assertTrue(che.byId(1)!!.path.containsKey(9), "업(1) path includes 남피(9)")
        val supplied = computeSuppliedCities(
            cities = listOf(SupplyCity(1, 1), SupplyCity(9, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = che,
        )
        assertEquals(setOf(1, 9), supplied)
        // pin: the 'che' base byId matches the GREEN CityConst base (zero delta).
        val baseNode: CityInitialDetail = CityConst.byId(1)!!
        assertEquals(baseNode.path.keys.toList(), che.byId(1)!!.path.keys.toList())
    }

    @Test
    fun `non-seat spatial provinces connect two owned cities without a CityConst edge`() {
        val supplied = computeSuppliedCitiesWithSpatialNetwork(
            cities = listOf(SupplyCity(1, 1), SupplyCity(3, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1, 1, 1),
                edges = listOf(0 to 1, 1 to 2),
                cityProvinces = mapOf(1 to 0, 3 to 2),
            ),
        )

        assertEquals(setOf(1, 3), supplied)
    }

    @Test
    fun `enemy occupied spatial province blocks the supply corridor`() {
        val supplied = computeSuppliedCitiesWithSpatialNetwork(
            cities = listOf(SupplyCity(1, 1), SupplyCity(3, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1, 2, 1),
                edges = listOf(0 to 1, 1 to 2),
                cityProvinces = mapOf(1 to 0, 3 to 2),
            ),
        )

        assertEquals(setOf(1), supplied)
    }

    @Test
    fun `same-owner spatial detour bypasses a blocked province`() {
        val supplied = computeSuppliedCitiesWithSpatialNetwork(
            cities = listOf(SupplyCity(1, 1), SupplyCity(3, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1, 2, 1, 1),
                edges = listOf(0 to 1, 1 to 2, 0 to 3, 3 to 2),
                cityProvinces = mapOf(1 to 0, 3 to 2),
            ),
        )

        assertEquals(setOf(1, 3), supplied)
    }

    @Test
    fun `unmapped legacy city keeps CityConst supply without becoming a spatial node`() {
        val supplied = computeSuppliedCitiesWithSpatialNetwork(
            cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1),
                edges = emptyList(),
                cityProvinces = mapOf(1 to 0),
            ),
        )

        assertEquals(setOf(1, 2), supplied)
    }

    @Test
    fun `unmapped legacy capital cannot supply a mapped city through the city graph`() {
        val supplied = computeSuppliedCitiesWithSpatialNetwork(
            cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1),
                edges = emptyList(),
                cityProvinces = mapOf(2 to 0),
            ),
        )

        assertEquals(setOf(1), supplied)
    }

    @Test
    fun `multiple nations traverse only their own spatial components`() {
        val supplied = computeSuppliedCitiesWithSpatialNetwork(
            cities = listOf(
                SupplyCity(1, 1),
                SupplyCity(2, 1),
                SupplyCity(3, 2),
                SupplyCity(4, 2),
            ),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(3, 2)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1, 1, 2, 2, 2),
                edges = listOf(0 to 1, 1 to 2, 2 to 3),
                cityProvinces = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 4),
            ),
        )

        assertEquals(setOf(1, 2, 3, 4), supplied)
    }

    @Test
    fun `dual reachability verdicts protect mismatches unless a reviewed cut is upheld`() {
        val network = spatialNetwork(
            owners = listOf(1, 1, 1, 1, 1),
            edges = listOf(0 to 3),
            cityProvinces = mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3, 5 to 4),
        ).copy(
            fallbackPolicies = mapOf(
                2 to SupplyFallbackPolicy(
                    SupplyDisconnectionDecision.PROTECT_GEOMETRY_DEFECT,
                    "PARENT-0000@401:224",
                ),
                3 to SupplyFallbackPolicy(
                    SupplyDisconnectionDecision.UPHOLD_HISTORICAL_EXCLAVE,
                    "PARENT-0099@1:1",
                ),
            ),
        )

        val evaluation = evaluateSupplyReachability(
            cities = listOf(
                SupplyCity(5, 1), SupplyCity(3, 1), SupplyCity(1, 1),
                SupplyCity(4, 1), SupplyCity(2, 1),
            ),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = verdictConst(),
            spatialNetwork = network,
        )

        assertContentEquals(listOf(1, 2, 3, 4, 5), evaluation.rows.map { it.cityId })
        assertContentEquals(
            listOf(
                SupplyReachabilityVerdict.BOTH_SUPPLIED,
                SupplyReachabilityVerdict.CITY_ONLY_PROTECTED,
                SupplyReachabilityVerdict.SPATIAL_CUT_UPHELD,
                SupplyReachabilityVerdict.SPATIAL_ONLY_SUPPLIED,
                SupplyReachabilityVerdict.BOTH_UNSUPPLIED,
            ),
            evaluation.rows.map { it.verdict },
        )
        assertEquals(setOf(1, 2, 4), evaluation.suppliedCityIds)
    }

    @Test
    fun `unreviewed city-only mismatch is runtime protected and an unmapped city never bridges provinces`() {
        val evaluation = evaluateSupplyReachability(
            cities = listOf(SupplyCity(1, 1), SupplyCity(2, 1), SupplyCity(3, 1)),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            spatialNetwork = spatialNetwork(
                owners = listOf(1, 1),
                edges = emptyList(),
                cityProvinces = mapOf(1 to 0, 3 to 1),
            ),
        )

        assertTrue(evaluation.rows.none { it.cityId == 2 }, "unmapped legacy cities are not canonical spatial disagreements")
        assertEquals(SupplyReachabilityVerdict.BOTH_UNSUPPLIED, evaluation.rows.single { it.cityId == 3 }.verdict)
        assertEquals(setOf(1, 2), evaluation.suppliedCityIds)
    }
}
