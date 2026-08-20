package opensamguk.logic.war

import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant

/**
 * BO2 — port target = PHP `func.php:2010-2066` (`searchDistanceListToDest`) + the candidateCities
 * derivation (`che_출병.php:163-193`). PURE — no RNG, no DB; the choice draw lives in BO3.
 *
 * The PHP function resolves `allowedCityList` from a DB query (`SELECT city, nation FROM city WHERE nation
 * IN linkNationList`); the pure module takes the already-resolved [allowedCityList] (city→nation) the engine
 * builder stages in. The BFS otherwise transcribes the SplQueue dist-bucketing exactly: enqueue `[to, 0]`,
 * dequeue-skip-visited, record a `result[dist]` bucket when a from-NEIGHBOUR is reached, and expand only
 * through allowed cities — the visited-set order and neighbour-iteration order match [CityConst.path].
 */

/**
 * Dist-bucketed BFS from [to] outward, returning `dist → [(cityId, nationId), …]` in BFS discovery
 * insertion order, restricted to [allowedCityList]. Returns an empty map when [to] is not allowed
 * (PHP `:2034-2036`).
 *
 * @param from the attacker's city — its allowed direct path-neighbours are the bucket targets (`:2027-2032`).
 * @param to the final target city — the BFS root (`:2039`).
 * @param allowedCityList resolved city→nation map (my-state-0 partners + me + 0 neutral).
 */
fun searchDistanceListToDest(
    from: Int,
    to: Int,
    allowedCityList: Map<Int, Int>,
    cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
): Map<Int, List<Pair<Int, Int>>> {
    // remainFromCities = the from-city's path neighbours present in allowedCityList (PHP :2027-2032).
    val remainFromCities = LinkedHashSet<Int>()
    val fromCity = cityConst.byId(from)
    if (fromCity != null) {
        for (neighbourId in fromCity.path.keys) {
            if (allowedCityList.containsKey(neighbourId)) remainFromCities.add(neighbourId)
        }
    }

    // PHP :2034-2036 — an unreachable / non-allowed destination yields an empty list.
    if (!allowedCityList.containsKey(to)) return emptyMap()

    val result = LinkedHashMap<Int, MutableList<Pair<Int, Int>>>()
    val cities = HashSet<Int>()                 // PHP $cities visited-set
    val queue = ArrayDeque<IntArray>()
    queue.addLast(intArrayOf(to, 0))

    while (remainFromCities.isNotEmpty() && queue.isNotEmpty()) {
        val (cityId, dist) = queue.removeFirst().let { it[0] to it[1] }
        if (cityId in cities) continue
        cities.add(cityId)

        if (cityId in remainFromCities) {
            remainFromCities.remove(cityId)
            result.getOrPut(dist) { mutableListOf() }.add(cityId to allowedCityList.getValue(cityId))
        }

        val city = cityConst.byId(cityId) ?: continue
        for (connCityId in city.path.keys) {
            // PHP: skip neighbours not in allowedCityList, and already-visited cities (:2055-2060).
            if (!allowedCityList.containsKey(connCityId)) continue
            if (connCityId in cities) continue
            queue.addLast(intArrayOf(connCityId, dist + 1))
        }
    }

    return result
}

/**
 * The candidateCities do-while (PHP `che_출병.php:163-193`).
 *
 * minDist = the FIRST inserted key of [distanceList] (PHP `array_key_first`). Walk the buckets in insertion
 * order up to `minDist+1`; per bucket set `currDist` and collect the NOT-mine cities; if any candidate was
 * found after processing a bucket, stop (PHP `break 2`). If the whole minDist..minDist+1 band yields no
 * enemy, fall back to MY OWN cities at minDist (PHP `:187-192`).
 *
 * @return (candidateCityIds, currDist).
 */
fun candidateCities(
    distanceList: Map<Int, List<Pair<Int, Int>>>,
    myNationId: Int,
): Pair<List<Int>, Int> {
    val candidates = mutableListOf<Int>()
    var currDist = 999
    val minDist = distanceList.keys.firstOrNull() ?: return emptyList<Int>() to currDist

    // PHP do { … } while(false) — a single pass with break-2 semantics.
    for ((dist, distCitiesInfo) in distanceList) {
        if (dist > minDist + 1) break
        currDist = dist
        for ((distCityID, distCityNation) in distCitiesInfo) {
            if (distCityNation != myNationId) candidates.add(distCityID)
        }
        if (candidates.isNotEmpty()) return candidates to currDist   // PHP break 2
    }

    // fallback (PHP :187-192) — my own cities at minDist (reached only when the band had no enemy).
    for ((distCityID, distCityNation) in distanceList.getValue(minDist)) {
        if (distCityNation == myNationId) candidates.add(distCityID)
    }
    return candidates to currDist
}
