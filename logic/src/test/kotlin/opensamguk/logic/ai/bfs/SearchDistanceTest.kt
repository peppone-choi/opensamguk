package opensamguk.logic.ai.bfs

import opensamguk.common.constants.CityConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FB1 — port target = PHP `func.php:1895` (`searchDistance`).
 *
 * The AI REUSES the GREEN `CityConst.byId(x).path` (name-order adjacency, byte-identical to PHP
 * `array_keys(CityConst::byID($id)->path)`) — there is NO separate adjacency. This test first PROVES
 * (B6, R-BFS) the P4 graph's neighbour ordering is `$initCity` NAME-order, NOT numeric-by-id, then
 * exercises [searchDistance]: FIFO SplQueue, finalize-at-DEQUEUE (duplicate enqueues allowed,
 * first-dequeue wins), `distanceList[dist][]` + `cities[id]=dist` recorded in DEQUEUE order
 * (LinkedHashMap insertion), the `dist>=maxDist` frontier recorded-not-expanded, `distForm` dropping
 * the dist-0 bucket, and `from` included when `distForm=false`.
 *
 * Expected BFS traces are computed off the REAL CityConst adjacency (city 영릉=54, whose neighbours
 * 계양=30/남영=49/무릉=53/장사=14 appear in NAME order [30,49,53,14] — NOT numeric [14,30,49,53]).
 */
class SearchDistanceTest {

    @Test
    fun `B6 - CityConst path neighbour order is initCity NAME-order, not numeric-by-id`() {
        // 영릉(54)'s $initCity path = listOf("계양","남영","무릉","장사") → resolved IDs in NAME order
        // are [계양=30, 남영=49, 무릉=53, 장사=14]. A numeric-by-id sort would be [14,30,49,53].
        val nameOrderIds = CityConst.byId(54)!!.path.keys.toList()
        assertEquals(
            listOf(30, 49, 53, 14),
            nameOrderIds,
            "path.keys must be NAME-order ([계양,남영,무릉,장사]→[30,49,53,14]), not numeric",
        )
        assertTrue(
            nameOrderIds != nameOrderIds.sorted(),
            "the chosen proof city's name-order MUST differ from a numeric sort (else the assertion is vacuous)",
        )
        // Resolve the names back through CityConst to prove the IDs ARE the source name-list, in order.
        assertEquals(
            listOf("계양", "남영", "무릉", "장사"),
            nameOrderIds.map { CityConst.byId(it)!!.name },
            "the path key order resolves to the exact \$initCity connection name-list order",
        )
    }

    @Test
    fun `searchDistance distForm=false includes from at dist 0 and records neighbours in name-order`() {
        // The single PHP signature `searchDistance(from, maxDist, distForm)` returns the distForm=false
        // `cities` shape; the typed entry-point `searchDistanceCities` exposes the {cityId→dist} map.
        assertEquals(searchDistance(from = 54, maxDist = 1, distForm = false), AiDistance.searchDistanceCities(54, 1))
        // maxDist=1: from(54) at dist 0, then its neighbours in path.keys (name) order at dist 1.
        val cities = AiDistance.searchDistanceCities(from = 54, maxDist = 1)
        assertEquals(
            listOf(54 to 0, 30 to 1, 49 to 1, 53 to 1, 14 to 1),
            cities.entries.map { it.key to it.value },
            "cities recorded in DEQUEUE order (LinkedHashMap insertion): from then name-order neighbours",
        )
    }

    @Test
    fun `searchDistance distForm=true drops the dist-0 bucket and buckets by distance`() {
        // The single PHP signature returns the distForm=true `distanceList` shape (dist-0 dropped).
        assertEquals(searchDistance(from = 54, maxDist = 1, distForm = true), AiDistance.searchDistanceBuckets(54, 1))
        val distanceList = AiDistance.searchDistanceBuckets(from = 54, maxDist = 1)
        assertEquals(
            mapOf(1 to listOf(30, 49, 53, 14)),
            distanceList,
            "distForm drops dist 0; the dist-1 bucket preserves name-order DEQUEUE insertion",
        )
    }

    @Test
    fun `searchDistance frontier at dist greater-equal maxDist is recorded but not expanded`() {
        // At maxDist=1 the dist-1 neighbours of 54 are recorded; their own neighbours (e.g. 55,28,...)
        // are NOT enqueued. So the city set is exactly {54,30,49,53,14}.
        val cities = AiDistance.searchDistanceCities(from = 54, maxDist = 1)
        assertEquals(setOf(54, 30, 49, 53, 14), cities.keys, "the maxDist frontier is recorded-not-expanded")
    }

    @Test
    fun `searchDistance maxDist=2 expands the dist-1 frontier in dequeue order`() {
        // The dist-2 cities are discovered by expanding 30,49,53,14 in dequeue order, each over its
        // own path.keys (name order), with finalize-at-dequeue collapsing duplicate enqueues.
        val cities = AiDistance.searchDistanceCities(from = 54, maxDist = 2)
        assertEquals(
            listOf(54 to 0, 30 to 1, 49 to 1, 53 to 1, 14 to 1, 55 to 2, 28 to 2, 66 to 2, 13 to 2, 15 to 2),
            cities.entries.map { it.key to it.value },
            "dist-2 cities appended in BFS dequeue order off the name-ordered frontier",
        )
    }

    @Test
    fun `searchDistance finalize-at-dequeue - every city recorded once at its minimal distance`() {
        // A full BFS over the connected graph: a city reachable by multiple paths is finalized at the
        // FIRST (shortest, FIFO) dequeue. Assert each city appears exactly once and at its min dist.
        val cities = AiDistance.searchDistanceCities(from = 54, maxDist = 99)
        // No duplicate keys (LinkedHashMap already enforces this) — but assert the count equals the
        // distinct-city count and that a multi-path city (장사=14, reachable as a dist-1 neighbour and
        // also via other hops) is pinned at its minimal dist 1, proving first-dequeue-wins.
        assertEquals(94, cities.size, "all 94 connected cities finalized exactly once")
        assertEquals(1, cities.getValue(14), "장사(14) finalized at its minimal dist (first dequeue wins)")
        assertEquals(0, cities.getValue(54), "the from city is dist 0")
    }
}
