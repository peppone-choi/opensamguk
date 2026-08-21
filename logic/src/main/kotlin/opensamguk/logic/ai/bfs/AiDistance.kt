package opensamguk.logic.ai.bfs

import opensamguk.logic.domain.City
import opensamguk.logic.world.CityConstRegistry
import opensamguk.logic.world.CityConstVariant
import java.util.BitSet

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
 * Port target = PHP `func.php:1895` (`searchDistance`), `:2073` (`searchAllDistanceByCityList`,
 * Floyd-Warshall), `:2125` (`searchAllDistanceByNationList`), `:2139` (`isNeighbor`).
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
    fun searchDistance(
        from: Int,
        maxDist: Int = 99,
        distForm: Boolean = false,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, *> {
        return if (distForm) {
            searchDistanceBuckets(from, maxDist, cityConst)
        } else {
            searchDistanceCities(from, maxDist, cityConst)
        }
    }

    /** PHP `searchDistance(..., distForm=false)` — `{cityId → dist}` in dequeue order, `from` at dist 0. */
    fun searchDistanceCities(
        from: Int,
        maxDist: Int = 99,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, Int> {
        val (cities, _) = bfs(from, maxDist, cityConst)
        return cities
    }

    /** PHP `searchDistance(..., distForm=true)` — `{dist → [cityId, …]}` with the dist-0 bucket dropped. */
    fun searchDistanceBuckets(
        from: Int,
        maxDist: Int = 99,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, List<Int>> {
        val (_, distanceList) = bfs(from, maxDist, cityConst)
        distanceList.remove(0) // PHP unset($distanceList[0])
        return distanceList
    }

    /**
     * The shared BFS body. Returns (`cities` = `{cityId → dist}`, `distanceList` = `{dist → [cityId, …]}`),
     * both as insertion-ordered [LinkedHashMap]s reflecting DEQUEUE order. The dist-0 bucket is left in
     * `distanceList` here; [searchDistanceBuckets] drops it (PHP `unset`) after the walk.
     */
    private fun bfs(
        from: Int,
        maxDist: Int,
        cityConst: CityConstVariant,
    ): Pair<LinkedHashMap<Int, Int>, LinkedHashMap<Int, MutableList<Int>>> {
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

            val city = cityConst.byId(cityId) ?: continue
            for (connCityId in city.path.keys) { // name-order neighbour enqueue
                if (cities.containsKey(connCityId)) continue
                queue.addLast(intArrayOf(connCityId, dist + 1))
            }
        }

        return cities to distanceList
    }

    /**
     * Byte-equivalent port of PHP `func.php:2073-2117` (`searchAllDistanceByCityList`). Returns
     * `{from → {to → dist}}` for every ordered pair within [cityIDList].
     *
     * The ROW + INNER-MAP key order is a hard parity target (G3 §4, R-BFS):
     *  - outer rows iterate [cityIDList] in input (DB-row) order;
     *  - each row is seeded `{self → 0}` THEN each **in-list** neighbour in `path.keys` (NAME) order
     *    `→ 1` (`:2084-2092`) — so the initial inner key order is `self, name-order neighbours`;
     *  - the Floyd triple loop (`cityStop` outer × `cityFrom` mid × `cityTo` inner, all over the
     *    membership map = input order, `:2096-2113`) **APPENDS** each newly-discovered `[from][to]`
     *    pair to the row (`:2106-2108`) in discovery order, else `min`-relaxes (`:2111`). The inner
     *    map therefore ends `self → path-order neighbours → transitive-append discovery order` and is
     *    preserved here as a [LinkedHashMap] — **NEVER numerically resorted** (a resort would change
     *    `array_key_first`/`array_sum` consumers downstream).
     *
     * Running the literal Floyd triple loop costs more than 40 seconds for Han's 780 cities. This
     * implementation separates the two concerns without changing observable map order:
     *  - one restricted BFS per source caches shortest distances in `O(V * (V + E))`;
     *  - a bit-set transitive closure replays the PHP Floyd discovery order in `O(V^3 / wordSize)`.
     *
     * Empty [cityIDList] → empty map (`:2075-2077`). NO RNG draws.
     */
    fun searchAllDistanceByCityList(
        cityIDList: List<Int>,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, Map<Int, Int>> {
        if (cityIDList.isEmpty()) return emptyMap() // PHP `if (!$cityIDList) return [];`

        // $cityList[$cityID]=$cityID membership map, insertion (= input) order.
        val cityList = LinkedHashMap<Int, Int>()
        for (cityId in cityIDList) cityList[cityId] = cityId
        val cityIds = cityList.keys.toList()
        val indexByCity = cityIds.withIndex().associateTo(HashMap(cityIds.size)) { (index, cityId) ->
            cityId to index
        }

        // Per-row init: {self→0} then in-list path-order neighbours →1.
        val distanceList = LinkedHashMap<Int, LinkedHashMap<Int, Int>>()
        val reachable = Array(cityIds.size) { BitSet(cityIds.size) }
        for ((fromIndex, cityId) in cityIds.withIndex()) {
            val nearList = LinkedHashMap<Int, Int>()
            nearList[cityId] = 0
            reachable[fromIndex].set(fromIndex)
            val city = cityConst.byId(cityId)
            if (city != null) {
                for (nextCityId in city.path.keys) { // name-order neighbours
                    val nextIndex = indexByCity[nextCityId] ?: continue
                    nearList[nextCityId] = 1
                    reachable[fromIndex].set(nextIndex)
                }
            }
            distanceList[cityId] = nearList
        }

        val shortestBySource = Array(cityIds.size) { sourceIndex ->
            restrictedDistances(sourceIndex, cityIds, indexByCity, cityConst)
        }

        // Replay Floyd reachability in the exact stop/from/to order. BitSet OR replaces the inner
        // cityTo scan, while nextSetBit still appends newly discovered keys in input-index order.
        for (stopIndex in cityIds.indices) {
            for (fromIndex in cityIds.indices) {
                if (!reachable[fromIndex][stopIndex]) continue
                val newlyReachable = reachable[stopIndex].clone() as BitSet
                newlyReachable.andNot(reachable[fromIndex])
                var toIndex = newlyReachable.nextSetBit(0)
                while (toIndex >= 0) {
                    val distance = shortestBySource[fromIndex][toIndex]
                    check(distance >= 0) { "reachable city has no BFS distance" }
                    distanceList.getValue(cityIds[fromIndex])[cityIds[toIndex]] = distance
                    toIndex = newlyReachable.nextSetBit(toIndex + 1)
                }
                reachable[fromIndex].or(reachable[stopIndex])
            }
        }

        return distanceList
    }

    private fun restrictedDistances(
        sourceIndex: Int,
        cityIds: List<Int>,
        indexByCity: Map<Int, Int>,
        cityConst: CityConstVariant,
    ): IntArray {
        val distances = IntArray(cityIds.size) { -1 }
        val queue = ArrayDeque<Int>()
        distances[sourceIndex] = 0
        queue.addLast(sourceIndex)

        while (queue.isNotEmpty()) {
            val fromIndex = queue.removeFirst()
            val fromCity = cityConst.byId(cityIds[fromIndex]) ?: continue
            for (nextCityId in fromCity.path.keys) {
                val nextIndex = indexByCity[nextCityId] ?: continue
                if (distances[nextIndex] >= 0) continue
                distances[nextIndex] = distances[fromIndex] + 1
                queue.addLast(nextIndex)
            }
        }
        return distances
    }

    /**
     * Faithful port of PHP `func.php:2125-2137` (`searchAllDistanceByNationList`). Thin wrapper:
     * empty [linkNationList] → empty map (`:2127-2129`); otherwise selects the city ids owned by
     * those nations and forwards to [searchAllDistanceByCityList].
     *
     * **PK-ascending DB-row order is the hard requirement (R-FACADE §1):** PHP's
     * `SELECT city FROM city WHERE nation IN (...) [AND supply=1]` has NO `ORDER BY`, so MariaDB
     * returns rows in clustered-PK (city id ascending) order. [cityRows] is supplied by the caller in
     * that exact PK-ascending order (the AI world view materialises it `.sortedBy { it.id }`); this
     * helper FILTERS in place, PRESERVING the supplied row order — it does NOT re-sort. [suppliedCityOnly]
     * adds the literal `AND supply=1` filter (`:2132`). NO RNG draws.
     *
     * @param cityRows the full `city` table snapshot in PK-ascending order (the DB-row-order contract).
     */
    fun searchAllDistanceByNationList(
        linkNationList: List<Int>,
        cityRows: List<City>,
        suppliedCityOnly: Boolean = false,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, Map<Int, Int>> {
        if (linkNationList.isEmpty()) return emptyMap() // PHP `if (!$linkNationList) return [];`
        val nationSet = linkNationList.toHashSet()
        val cityIDList = cityRows
            .filter { it.nationId in nationSet && (!suppliedCityOnly || it.supplyState == 1) }
            .map { it.id }
        return searchAllDistanceByCityList(cityIDList, cityConst)
    }

    /**
     * Faithful port of PHP `func.php:2139-2167` (`isNeighbor`) — boolean nation adjacency.
     *
     *  - `n1 === n2 → false` (`:2141`), short-circuited BEFORE any city lookup;
     *  - build [nation1]'s city-id set (`:2154-2156`);
     *  - for each [nation2] city, iterate its `path.keys` (NAME order, `:2159`) and return `true` on
     *    the **first** neighbour that is in [nation1]'s set (`:2160-2162`); else `false` (`:2166`).
     *
     * `includeNoSupply=false` adds `AND supply=1` to BOTH the nation1 AND nation2 city queries
     * (`:2148-2152`). [cityRows] is the PK-ascending `city` snapshot; nation2's iteration order follows
     * the supplied row order. NO RNG draws.
     */
    fun isNeighbor(
        nation1: Int,
        nation2: Int,
        cityRows: List<City>,
        includeNoSupply: Boolean = true,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Boolean {
        if (nation1 == nation2) return false // PHP `if ($nation1 === $nation2) return false;`

        val nation1Cities = HashSet<Int>()
        for (c in cityRows) {
            if (c.nationId != nation1) continue
            if (!includeNoSupply && c.supplyState != 1) continue
            nation1Cities.add(c.id)
        }

        for (c in cityRows) {
            if (c.nationId != nation2) continue
            if (!includeNoSupply && c.supplyState != 1) continue
            val city = cityConst.byId(c.id) ?: continue
            for (adjCity in city.path.keys) { // name-order
                if (nation1Cities.contains(adjCity)) return true // first-hit
            }
        }

        return false
    }
}

/** Top-level alias matching the PHP free-function name `searchDistance(from, maxDist, distForm)`. */
fun searchDistance(
    from: Int,
    maxDist: Int = 99,
    distForm: Boolean = false,
    cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
): Map<Int, *> = AiDistance.searchDistance(from, maxDist, distForm, cityConst)
