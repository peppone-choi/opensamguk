package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F-MAP / FM1 — pure BFS over CityConst.path adjacency.
 *
 * Port target = PHP `calcCityDistance` (func.php:1943) + `searchDistance` (func.php:1895),
 * the underlying BFS the `NearCity` constraint (Constraint/NearCity.php) consumes via
 * `searchDistance($from, $arg, false)` then `key_exists($destCity, $dist)`.
 *
 * Concrete fixtures (verified from CityConst.kt $initCity rows):
 *   업(1)   path -> 남피(9), 복양(18), 호관(70), 계교(78), 관도(80)
 *   남피(9) path -> 업(1), 평원(36), 역경(77)
 *   평원(36) path -> 남피(9), 북해(37), 계교(78)
 *   북평(8) path -> 역경(77), 백랑(90)   (only two bridges into 북평)
 */
class CalcCityDistanceTest {

    @Test
    fun `from equals to is zero`() {
        // PHP: if ($from === $to) return 0;
        assertEquals(0, CalcCityDistance.calcCityDistance(1, 1))
    }

    @Test
    fun `adjacent cities are one hop`() {
        // 업(1) -> 남피(9) is a direct path edge.
        assertEquals(1, CalcCityDistance.calcCityDistance(1, 9))
        // bidirectional adjacency (golden-locked): 남피(9) -> 업(1) is also 1.
        assertEquals(1, CalcCityDistance.calcCityDistance(9, 1))
    }

    @Test
    fun `two hop distance`() {
        // 업(1) -> 남피(9) -> 평원(36): shortest is 2 hops.
        assertEquals(2, CalcCityDistance.calcCityDistance(1, 36))
    }

    @Test
    fun `three hop distance`() {
        // 업(1) -> 남피(9) -> 역경(77) -> 북평(8): shortest is 3 hops.
        assertEquals(3, CalcCityDistance.calcCityDistance(1, 8))
    }

    @Test
    fun `blocked cities are excluded from the path`() {
        // 북평(8) is only reachable through 역경(77) or 백랑(90).
        // Blocking both bridges -> 8 becomes unreachable -> null
        // (mirrors PHP: neighbor not in allowedCityList is skipped during BFS).
        assertNull(CalcCityDistance.calcCityDistance(1, 8, blockedCityIds = setOf(77, 90)))
        // Blocking only one bridge still leaves the other route: 업->남피->역경->북평 = 3.
        assertEquals(3, CalcCityDistance.calcCityDistance(1, 8, blockedCityIds = setOf(90)))
    }

    @Test
    fun `blocked destination is null`() {
        // PHP: if (!key_exists($to, $allowedCityList)) return null;
        assertNull(CalcCityDistance.calcCityDistance(1, 9, blockedCityIds = setOf(9)))
    }

    @Test
    fun `nearCity radius one matches the direct path neighbors`() {
        // 업(1) direct path neighbors: 남피(9), 복양(18), 호관(70), 계교(78), 관도(80).
        assertEquals(setOf(9, 18, 70, 78, 80), CalcCityDistance.nearCity(1, 1))
    }

    @Test
    fun `nearCity excludes the origin city itself`() {
        assertTrue(1 !in CalcCityDistance.nearCity(1, 1))
        assertTrue(9 !in CalcCityDistance.nearCity(9, 2))
    }

    @Test
    fun `nearCity radius two is a superset of radius one`() {
        val r1 = CalcCityDistance.nearCity(1, 1)
        val r2 = CalcCityDistance.nearCity(1, 2)
        assertTrue(r2.containsAll(r1))
        // 평원(36) is exactly 2 hops away, so it appears at radius 2 but not radius 1.
        assertTrue(36 in r2)
        assertTrue(36 !in r1)
    }

    @Test
    fun `searchDistance returns the dist map including origin at zero`() {
        // Faithful port of PHP searchDistance($from, $maxDist, false): cities[from] = 0.
        val dist = CalcCityDistance.searchDistance(1, 1)
        assertEquals(0, dist[1])
        assertEquals(1, dist[9])
        assertEquals(1, dist[80])
        // radius 1 stops expanding past dist 1, so 2-hop 평원(36) is absent.
        assertTrue(36 !in dist)
    }

    @Test
    fun `nearCity radius one membership equals NearCity constraint semantics`() {
        // PHP NearCity.test(): key_exists(destCity, searchDistance(from, arg, false)).
        // For a real dest (never the origin), membership in nearCity must agree.
        val dist = CalcCityDistance.searchDistance(1, 2)
        val near = CalcCityDistance.nearCity(1, 2)
        for (cityId in dist.keys) {
            if (cityId == 1) continue
            assertTrue(cityId in near, "dest $cityId within radius should be in nearCity set")
        }
    }

    @Test
    fun `Han duplicate city names retain both numeric neighbors`() {
        val han = CityConstRegistry.of("han")
        // id 419 는 형주 「석」(析), id 595 는 익주 「석」(锡) 이다 — 같은 한글명이 붙은 서로
        // 다른 두 城. 재번호매김이 둘 중 하나를 다른 城으로 옮기면 이 단언이 먼저 빨개진다.
        assertEquals("석", han.byId(419)!!.name)
        assertEquals(han.regionIdByName("형주"), han.byId(419)!!.region)
        assertEquals("석", han.byId(595)!!.name)
        assertEquals(han.regionIdByName("익주"), han.byId(595)!!.region)
        assertTrue(419 in CalcCityDistance.nearCity(3, 1, han))
        assertTrue(595 in CalcCityDistance.nearCity(3, 1, han))
        assertTrue(CityConstRegistry.of("che").byId(419) == null)
    }

    @Test
    fun `world v2 Lu and Licheng are reciprocal one hop without changing che`() {
        val v2 = CityConstRegistry.of("han-world-v2")
        assertEquals(1, CalcCityDistance.calcCityDistance(273, 781, cityConst = v2))
        assertEquals(1, CalcCityDistance.calcCityDistance(781, 273, cityConst = v2))
        assertTrue(781 in CalcCityDistance.nearCity(273, 1, v2))
        assertTrue(273 in CalcCityDistance.nearCity(781, 1, v2))
        assertEquals(setOf(9, 18, 70, 78, 80), CalcCityDistance.nearCity(1, 1))
    }
}
