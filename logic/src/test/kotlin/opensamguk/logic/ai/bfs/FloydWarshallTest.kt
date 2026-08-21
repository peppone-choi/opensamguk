package opensamguk.logic.ai.bfs

import opensamguk.logic.domain.City
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FB2 — port target = PHP `func.php:2073` (`searchAllDistanceByCityList`, Floyd-Warshall),
 * `func.php:2125` (`searchAllDistanceByNationList`), `func.php:2139` (`isNeighbor`).
 *
 * All three REUSE the GREEN `CityConst.byId(x).path` name-order adjacency (R-BFS). NO RNG draws —
 * they only fix candidate ORDER. The load-bearing parity targets:
 *  - **Floyd row key order = self → in-list path-order neighbours → transitive-append discovery order**
 *    (cityStop outer × cityFrom mid × cityTo inner loop), preserved as a `LinkedHashMap`, NEVER
 *    numerically resorted (G3 §4, R-BFS).
 *  - **`searchAllDistanceByNationList` preserves DB-row order = PK-ascending** (R-FACADE §1): the
 *    supplied city rows are already PK-ascending; the helper filters `nation IN (...) [AND supply=1]`
 *    preserving that order, then forwards to Floyd.
 *  - **`isNeighbor`: `n1===n2 → false`; first-hit** over nation2's cities × their `path.keys` against
 *    the nation1 city set (G3 §6); `includeNoSupply=false` adds `AND supply=1` to BOTH queries.
 *
 * The expected Floyd rows are computed off the REAL CityConst adjacency:
 *   54(영릉)→[30,49,53,14] · 30(계양)→[14,54,55] · 49(남영)→[28,54,66] ·
 *   53(무릉)→[13,14,54] · 14(장사)→[13,15,30,53,54].
 */
class FloydWarshallTest {

    private fun city(id: Int, nation: Int, supply: Int = 1): City =
        City(
            id = id, nationId = nation, level = 5,
            commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
            supplyState = supply, frontState = 0, trust = 0.0,
        )

    // ---- searchAllDistanceByCityList (Floyd-Warshall row-order) ---------------------------------

    @Test
    fun `Floyd row key order is self then path-order neighbours then transitive-append, no numeric resort`() {
        // cityIDList [54,30,53,14]; 49/55/28/66/13/15 are NOT in-list so they are not row entries.
        // Expected (computed off the real adjacency, mirroring the PHP triple loop exactly):
        //   54 -> {54=0, 30=1, 53=1, 14=1}          (self, in-list name-order neighbours 30,53,14)
        //   30 -> {30=0, 14=1, 54=1, 53=2}          (self, name-order 14,54; then 53 appended via 14)
        //   53 -> {53=0, 14=1, 54=1, 30=2}          (self, name-order 14,54; then 30 appended via 14)
        //   14 -> {14=0, 30=1, 53=1, 54=1}          (self, name-order 30,53,54 — all already dist 1)
        val result = AiDistance.searchAllDistanceByCityList(listOf(54, 30, 53, 14))

        assertEquals(listOf(54, 30, 53, 14), result.keys.toList(), "outer row order = input (cityIDList) order")

        // Each row's INNER key order is a parity target (self → name-order neighbours → discovery append).
        assertEquals(listOf(54, 30, 53, 14), result.getValue(54).keys.toList())
        assertEquals(listOf(30, 14, 54, 53), result.getValue(30).keys.toList())
        assertEquals(listOf(53, 14, 54, 30), result.getValue(53).keys.toList())
        assertEquals(listOf(14, 30, 53, 54), result.getValue(14).keys.toList())

        // And the distances (min-relax) — assert the full row maps value-for-value.
        assertEquals(mapOf(54 to 0, 30 to 1, 53 to 1, 14 to 1), result.getValue(54))
        assertEquals(mapOf(30 to 0, 14 to 1, 54 to 1, 53 to 2), result.getValue(30))
        assertEquals(mapOf(53 to 0, 14 to 1, 54 to 1, 30 to 2), result.getValue(53))
        assertEquals(mapOf(14 to 0, 30 to 1, 53 to 1, 54 to 1), result.getValue(14))

        // A numeric resort of row 30 would be [30,53,54,14]; prove the actual order differs from it.
        assertTrue(
            result.getValue(30).keys.toList() != result.getValue(30).keys.toList().sorted(),
            "row 30's inner key order must be discovery order, NOT numeric-by-id",
        )
    }

    @Test
    fun `Floyd over full connected set preserves transitive-append discovery order`() {
        // cityIDList [54,30,49,53,14] — 49 now in-list, adds a dist-2 hop.
        val result = AiDistance.searchAllDistanceByCityList(listOf(54, 30, 49, 53, 14))
        assertEquals(listOf(54, 30, 49, 53, 14), result.keys.toList())

        assertEquals(mapOf(54 to 0, 30 to 1, 49 to 1, 53 to 1, 14 to 1), result.getValue(54))
        assertEquals(mapOf(30 to 0, 14 to 1, 54 to 1, 49 to 2, 53 to 2), result.getValue(30))
        assertEquals(mapOf(49 to 0, 54 to 1, 30 to 2, 53 to 2, 14 to 2), result.getValue(49))
        assertEquals(mapOf(53 to 0, 14 to 1, 54 to 1, 30 to 2, 49 to 2), result.getValue(53))
        assertEquals(mapOf(14 to 0, 30 to 1, 53 to 1, 54 to 1, 49 to 2), result.getValue(14))

        // Inner key orders (discovery append, not numeric).
        assertEquals(listOf(30, 14, 54, 49, 53), result.getValue(30).keys.toList())
        assertEquals(listOf(49, 54, 30, 53, 14), result.getValue(49).keys.toList())
    }

    @Test
    fun `optimized closure is entry-and-order identical to literal Floyd for che and han subsets`() {
        val cases = listOf(
            CityConstRegistry.of("che") to listOf(54, 30, 49, 53, 14, 15),
            CityConstRegistry.of("han") to listOf(780, 779, 778, 777, 776, 775, 774, 773),
        )

        for ((cityConst, cityIds) in cases) {
            val expected = literalFloyd(cityIds, cityConst)
            val actual = AiDistance.searchAllDistanceByCityList(cityIds, cityConst)
            assertEquals(expected, actual)
            assertEquals(expected.keys.toList(), actual.keys.toList())
            for (cityId in expected.keys) {
                assertEquals(expected.getValue(cityId).keys.toList(), actual.getValue(cityId).keys.toList())
            }
        }
    }

    @Test
    fun `Floyd empty cityIDList returns empty map`() {
        assertEquals(emptyMap(), AiDistance.searchAllDistanceByCityList(emptyList()))
    }

    private fun literalFloyd(
        cityIds: List<Int>,
        cityConst: CityConstVariant,
    ): Map<Int, Map<Int, Int>> {
        val membership = cityIds.associateWithTo(LinkedHashMap()) { it }
        val result = cityIds.associateWithTo(LinkedHashMap()) { cityId ->
            linkedMapOf(cityId to 0).apply {
                cityConst.byId(cityId)?.path?.keys?.forEach { nextCityId ->
                    if (nextCityId in membership) this[nextCityId] = 1
                }
            }
        }
        for (stop in membership.keys) {
            for (from in membership.keys) {
                for (to in membership.keys) {
                    val left = result.getValue(from)[stop] ?: continue
                    val right = result.getValue(stop)[to] ?: continue
                    result.getValue(from)[to] = minOf(result.getValue(from)[to] ?: Int.MAX_VALUE, left + right)
                }
            }
        }
        return result
    }

    // ---- searchAllDistanceByNationList (PK-ascending DB-row order → Floyd) -----------------------

    @Test
    fun `searchAllDistanceByNationList preserves PK-ascending DB-row order then forwards to Floyd`() {
        // The world view supplies city rows PK-ascending (R-FACADE §1). Here rows are deliberately
        // given PK-ascending; filtering nation IN (1,2) keeps them in that order: [14,30,53,54].
        val cityRows = listOf(
            city(14, nation = 1), city(30, nation = 2), city(49, nation = 9),
            city(53, nation = 1), city(54, nation = 2),
        )
        val result = AiDistance.searchAllDistanceByNationList(listOf(1, 2), cityRows)
        // cityIDList = [14,30,53,54] (PK-ascending, 49 filtered out as nation 9).
        assertEquals(listOf(14, 30, 53, 54), result.keys.toList(), "row order = PK-ascending DB-row order")
        // Floyd over [14,30,53,54] (49 absent so no dist-2 via 49):
        //   14 -> {14=0, 30=1, 53=1, 54=1}
        //   30 -> {30=0, 14=1, 54=1, 53=2}
        //   53 -> {53=0, 14=1, 54=1, 30=2}
        //   54 -> {54=0, 30=1, 53=1, 14=1}
        assertEquals(mapOf(14 to 0, 30 to 1, 53 to 1, 54 to 1), result.getValue(14))
        assertEquals(mapOf(30 to 0, 14 to 1, 54 to 1, 53 to 2), result.getValue(30))
        assertEquals(mapOf(53 to 0, 14 to 1, 54 to 1, 30 to 2), result.getValue(53))
        assertEquals(mapOf(54 to 0, 30 to 1, 53 to 1, 14 to 1), result.getValue(54))
    }

    @Test
    fun `searchAllDistanceByNationList suppliedCityOnly filters supply=1 keeping PK-ascending order`() {
        val cityRows = listOf(
            city(14, nation = 1, supply = 1),
            city(30, nation = 1, supply = 0), // unsupplied → dropped when suppliedCityOnly
            city(53, nation = 1, supply = 1),
            city(54, nation = 1, supply = 1),
        )
        val included = AiDistance.searchAllDistanceByNationList(listOf(1), cityRows, suppliedCityOnly = true)
        assertEquals(listOf(14, 53, 54), included.keys.toList(), "supply=0 city 30 dropped, order preserved")

        val all = AiDistance.searchAllDistanceByNationList(listOf(1), cityRows, suppliedCityOnly = false)
        assertEquals(listOf(14, 30, 53, 54), all.keys.toList(), "default includes unsupplied cities")
    }

    @Test
    fun `searchAllDistanceByNationList empty nation list returns empty`() {
        assertEquals(emptyMap(), AiDistance.searchAllDistanceByNationList(emptyList(), listOf(city(14, 1))))
    }

    // ---- isNeighbor (n1===n2 → false, first-hit) ------------------------------------------------

    @Test
    fun `isNeighbor same nation is always false`() {
        val rows = listOf(city(54, 1), city(30, 2))
        assertFalse(AiDistance.isNeighbor(1, 1, rows), "n1 === n2 must short-circuit to false (PHP :2141)")
    }

    @Test
    fun `isNeighbor true when a nation2 city borders a nation1 city`() {
        // nation1 owns 54; nation2 owns 30. 30's path = [14,54,55] → 54 ∈ nation1 cities → true.
        val rows = listOf(city(54, nation = 1), city(30, nation = 2))
        assertTrue(AiDistance.isNeighbor(1, 2, rows))
        // symmetric direction also holds (54's path includes 30).
        assertTrue(AiDistance.isNeighbor(2, 1, rows))
    }

    @Test
    fun `isNeighbor false when nations are not adjacent`() {
        // nation1 owns 54; nation2 owns 15 (시상). 15 is NOT a neighbour of 54, nor 54 of 15.
        val rows = listOf(city(54, nation = 1), city(15, nation = 2))
        assertFalse(AiDistance.isNeighbor(1, 2, rows))
    }

    @Test
    fun `isNeighbor includeNoSupply=false filters supply=1 on both sides`() {
        // nation1 owns 54 (supplied); nation2 owns 30 (UNsupplied). With includeNoSupply=false the
        // unsupplied 30 is excluded from nation2's query → no border found → false.
        val rows = listOf(city(54, nation = 1, supply = 1), city(30, nation = 2, supply = 0))
        assertTrue(AiDistance.isNeighbor(1, 2, rows, includeNoSupply = true), "default counts unsupplied 30")
        assertFalse(
            AiDistance.isNeighbor(1, 2, rows, includeNoSupply = false),
            "supply=1 filter drops unsupplied 30 → no adjacency",
        )
    }
}
