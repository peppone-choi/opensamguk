package opensamguk.logic.war

import opensamguk.common.constants.CityConst
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BO2 — port target = PHP `func.php:2010-2066` (`searchDistanceListToDest`) + the candidateCities derivation
 * (`che_출병.php:163-193`).
 *
 * `searchDistanceListToDest(from, to, allowedCityList)` is the dist-bucketed BFS over the GREEN
 * [CityConst] path adjacency (the same graph [CalcCityDistance] walks). It returns
 * `dist → [(cityId, nationId), …]` in BFS dist-discovery insertion order, restricted to the
 * caller-resolved `allowedCityList` (city→nation map = the my-state-0 diplomacy partners + me + 0 neutral).
 *
 * [candidateCities] then mirrors the `che_출병` do-while:
 *  - minDist..minDist+1 cities NOT mine are the candidates;
 *  - if NONE found in that band, fall back to my OWN cities at minDist.
 */
class SearchDistanceTest {

    // Build a small allowed city→nation map over the real CityConst adjacency. Pick a hub city with
    // multiple direct neighbours: 업(1) → 남피(9), 복양(18), 호관(?), 계교(?), 관도(?).
    private val ME = 1
    private val ENEMY = 2

    @Test
    fun `BFS buckets from-neighbours by distance from the target over CityConst adjacency`() {
        // from = 업(1); to = 남피(9). 업's neighbours: 남피(9), 복양(18), 호관, 계교, 관도.
        // The result buckets a from-NEIGHBOUR by its distance from the TARGET (남피=9).
        //   - 남피(9) is both a from-neighbour AND the target → dist 0.
        //   - 복양(18) is a from-neighbour at dist 2 from 남피 (9 → 업(1) → 복양(18)).
        val from = 1
        val to = 9
        val allowed = linkedMapOf(
            1 to ME,     // 업 (the from city — traversable, allowed)
            9 to ENEMY,  // 남피 (target, dist 0 — also a from-neighbour)
            18 to ENEMY, // 복양 (a from-neighbour, dist 2 from 남피)
        )
        val result = searchDistanceListToDest(from, to, allowed)
        val flat = result.entries.flatMap { (d, lst) -> lst.map { Triple(d, it.first, it.second) } }

        assertTrue(flat.any { it == Triple(0, 9, ENEMY) }, "남피(9)=target is a from-neighbour at dist 0: $result")
        assertTrue(flat.any { it == Triple(2, 18, ENEMY) }, "복양(18) from-neighbour is dist 2 from 남피: $result")
    }

    @Test
    fun `allowed-nation filter blocks traversal through non-allowed cities`() {
        // If 'to' is not in allowedCityList, PHP returns [] (empty).
        val from = 1
        val to = 9
        val allowedWithoutTo = linkedMapOf(1 to ME)   // 'to'(9) not present
        assertEquals(emptyMap(), searchDistanceListToDest(from, to, allowedWithoutTo))
    }

    @Test
    fun `candidateCities picks not-mine cities in the minDist band`() {
        // distanceList: dist 1 → [(9, ENEMY)], dist 1 also (10, ME). The not-mine band yields [9].
        val distanceList = linkedMapOf(
            1 to listOf(9 to ENEMY, 10 to ME),
            2 to listOf(11 to ENEMY),
        )
        val (cands, currDist) = candidateCities(distanceList, ME)
        assertEquals(listOf(9), cands, "only not-mine cities in the minDist band are candidates")
        assertEquals(1, currDist)
    }

    @Test
    fun `candidateCities falls back to my own cities at minDist when no enemy near`() {
        // minDist band (1..2) has only MY cities → fall back to my own cities at minDist.
        val distanceList = linkedMapOf(
            1 to listOf(9 to ME, 10 to ME),
            2 to listOf(11 to ME),
        )
        val (cands, _) = candidateCities(distanceList, ME)
        // fallback collects my own at minDist (dist 1): [9, 10].
        assertEquals(listOf(9, 10), cands)
    }

    @Test
    fun `candidateCities scans up to minDist plus 1 for enemies`() {
        // No enemy at minDist(1), but an enemy at minDist+1(2) → that enemy is the candidate.
        val distanceList = linkedMapOf(
            1 to listOf(9 to ME),
            2 to listOf(11 to ENEMY),
        )
        val (cands, currDist) = candidateCities(distanceList, ME)
        assertEquals(listOf(11), cands)
        assertEquals(2, currDist, "currDist advances to minDist+1 when the enemy is found there")
    }
}
