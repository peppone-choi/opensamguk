package opensamguk.logic.ai.bfs

import opensamguk.common.constants.CityConst

/**
 * F-BFS (P5) — the AI's distance/adjacency helpers.
 *
 * **DEFINITIVE (R-BFS, B6): the AI REUSES the GREEN [CityConst.byId]`(x).path` name-order adjacency
 * + the P4 `war/SearchDistanceListToDest` primitive — it builds NO separate adjacency.** Both the PHP
 * `CityConstBase::_generate()` and the Kotlin [CityConst.generateCities] build `path` as a
 * `LinkedHashMap<Int,String>` in `$initCity` connection-NAME source order; there is NO numeric sort
 * anywhere. `CityConst.byId(x).path.keys` therefore iterates IDs in NAME-order, byte-identical to PHP
 * `array_keys(CityConst::byID($id)->path)`. This file only adds the BFS/distance wrappers PHP's AI
 * consumers call over that GREEN adjacency. NO RNG draws — these helpers only fix candidate ORDER.
 *
 * Port target = PHP `func.php:1895` (`searchDistance`). FB2 extends this file with
 * `searchAllDistanceByCityList` (Floyd-Warshall), `searchAllDistanceByNationList`, and `isNeighbor`.
 */
object AiDistance {

    /**
     * Faithful port of PHP `func.php:1895-1934` (`searchDistance`).
     *
     * Dist-bucketed BFS from [from] outward over the GREEN [CityConst] name-order adjacency:
     *  - a single FIFO `SplQueue` seeded with `[from, 0]`;
     *  - **finalize-at-DEQUEUE** — `if (key_exists(cityID, cities)) continue` (`:1907-1909`): duplicate
     *    enqueues are allowed and the FIRST dequeue (shortest, by FIFO) wins;
     *  - on dequeue, append to `distanceList[dist][]` THEN set `cities[cityID]=dist` (`:1911-1916`),
     *    both in DEQUEUE order (preserved by [LinkedHashMap] insertion);
     *  - the `dist >= maxDist` frontier is recorded but NOT expanded (`:1917-1919`);
     *  - neighbours are enqueued in `path.keys` (NAME) order, skipping already-finalized cities
     *    (`:1921-1926`).
     *
     * @param from the BFS root city id.
     * @param maxDist the inclusive frontier distance (default 99 — effectively the whole map).
     * @param distForm return shape (PHP `:1928-1933`):
     *  - `false` → `cities` = `{cityId → dist}` (the `from` city included at dist 0);
     *  - `true`  → `distanceList` = `{dist → [cityId, …]}` with the dist-0 bucket dropped (`unset[0]`).
     *
     * The concrete return type is `Map<Int, Int>` when [distForm] is `false` and `Map<Int, List<Int>>`
     * when `true`; callers select the helper that matches their PHP call-site, so the two shapes are
     * exposed as distinct entry points to keep the static type honest.
     */
    fun searchDistance(from: Int, maxDist: Int = 99, distForm: Boolean = false): Map<Int, *> {
        return if (distForm) searchDistanceBuckets(from, maxDist) else searchDistanceCities(from, maxDist)
    }

    /** PHP `searchDistance(..., distForm=false)` — `{cityId → dist}` in dequeue order, `from` at dist 0. */
    fun searchDistanceCities(from: Int, maxDist: Int = 99): Map<Int, Int> {
        val (cities, _) = bfs(from, maxDist)
        return cities
    }

    /** PHP `searchDistance(..., distForm=true)` — `{dist → [cityId, …]}` with the dist-0 bucket dropped. */
    fun searchDistanceBuckets(from: Int, maxDist: Int = 99): Map<Int, List<Int>> {
        val (_, distanceList) = bfs(from, maxDist)
        distanceList.remove(0) // PHP unset($distanceList[0])
        return distanceList
    }

    /**
     * The shared BFS body. Returns (`cities` = `{cityId → dist}`, `distanceList` = `{dist → [cityId, …]}`),
     * both as insertion-ordered [LinkedHashMap]s reflecting DEQUEUE order. The dist-0 bucket is left in
     * `distanceList` here; [searchDistanceBuckets] drops it (PHP `unset`) after the walk.
     */
    private fun bfs(from: Int, maxDist: Int): Pair<LinkedHashMap<Int, Int>, LinkedHashMap<Int, MutableList<Int>>> {
        val cities = LinkedHashMap<Int, Int>()
        val distanceList = LinkedHashMap<Int, MutableList<Int>>()
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(from, 0))

        while (queue.isNotEmpty()) {
            val head = queue.removeFirst()
            val cityId = head[0]
            val dist = head[1]
            if (cities.containsKey(cityId)) continue // finalize-at-dequeue: first dequeue wins

            distanceList.getOrPut(dist) { mutableListOf() }.add(cityId)
            cities[cityId] = dist
            if (dist >= maxDist) continue // frontier recorded, not expanded

            val city = CityConst.byId(cityId) ?: continue
            for (connCityId in city.path.keys) { // name-order neighbour enqueue
                if (cities.containsKey(connCityId)) continue
                queue.addLast(intArrayOf(connCityId, dist + 1))
            }
        }

        return cities to distanceList
    }
}

/** Top-level alias matching the PHP free-function name `searchDistance(from, maxDist, distForm)`. */
fun searchDistance(from: Int, maxDist: Int = 99, distForm: Boolean = false): Map<Int, *> =
    AiDistance.searchDistance(from, maxDist, distForm)
