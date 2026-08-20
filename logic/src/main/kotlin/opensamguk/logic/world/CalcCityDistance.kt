package opensamguk.logic.world

/**
 * F-MAP / FM1 — minimal city-distance map module.
 *
 * Faithful port of PHP `calcCityDistance` (legacy/devsam-core/hwe/func.php:1943) and
 * `searchDistance` (func.php:1895), the two pure BFS routines over `CityConst`'s
 * golden-locked bidirectional `path` adjacency (name->id, built in CityConst._generate()).
 *
 * Minimal scope: pure BFS only. The PHP `linkNationList` allow-list is DB-driven
 * (`SELECT city FROM city WHERE nation IN (...)`); the pure module cannot resolve
 * city->nation ownership, so the path-restriction is expressed here as a caller-supplied
 * set of blocked city ids (the resolved exclusion set). The BFS structure, visited-set
 * order, neighbour-iteration order, and the `from`-not-filtered quirk are preserved exactly.
 *
 * Full pathfinding (the HasRoute* / NearNation, C-DB constraint family) is DEFERRED to the
 * map/diplomacy phase — see plan AREA F-MAP and Open Question 9.
 */
object CalcCityDistance {

    /**
     * Port of PHP `calcCityDistance(int $from, int $to, ?array $linkNationList): ?int`.
     *
     * Returns the hop-count distance from [from] to [to] over the city adjacency, or `null`
     * when [to] is unreachable / blocked. Callers fall back to `?? 50` (e.g. che_천도).
     *
     * @param blockedCityIds cities excluded from traversal (the resolved path-restriction set,
     *   mirroring PHP's "neighbour not in allowedCityList -> skip"). `null` = no restriction
     *   (PHP `$linkNationList === null` => `allowedCityList = CityConst::all()`).
     */
    fun calcCityDistance(
        from: Int,
        to: Int,
        blockedCityIds: Set<Int>? = null,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Int? {
        // PHP: if (!key_exists($to, $allowedCityList)) return null;
        // (a blocked destination can never be reached).
        if (blockedCityIds != null && to in blockedCityIds) {
            return null
        }

        // PHP: if ($from === $to) return 0;
        if (from == to) {
            return 0
        }

        val visited = HashSet<Int>()
        val queue = ArrayDeque<IntArray>()
        // PHP enqueues [$from, 0] unconditionally — $from is NOT filtered by allowedCityList.
        queue.addLast(intArrayOf(from, 0))

        while (queue.isNotEmpty()) {
            val (cityId, dist) = queue.removeFirst().let { it[0] to it[1] }
            if (cityId in visited) {
                continue
            }
            visited.add(cityId)

            if (cityId == to) {
                return dist
            }

            // PHP: foreach (array_keys(CityConst::byID($cityID)->path) ...) — neighbour ids,
            // in CityConst path insertion order (LinkedHashMap key order).
            val city = cityConst.byId(cityId) ?: continue
            for (connCityId in city.path.keys) {
                if (blockedCityIds != null && connCityId in blockedCityIds) {
                    continue
                }
                if (connCityId in visited) {
                    continue
                }
                queue.addLast(intArrayOf(connCityId, dist + 1))
            }
        }

        // 길이 없음 — no path.
        return null
    }

    /**
     * Port of PHP `searchDistance(int $from, int $maxDist, bool $distForm=false)` with the
     * `$distForm=false` branch (the one `NearCity` consumes): returns `cities[$cityID] = $dist`
     * for every city within [maxDist] hops of [from], INCLUDING [from] itself at distance 0.
     */
    fun searchDistance(
        from: Int,
        maxDist: Int = 99,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, Int> {
        val cities = LinkedHashMap<Int, Int>()
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(from, 0))

        while (queue.isNotEmpty()) {
            val (cityId, dist) = queue.removeFirst().let { it[0] to it[1] }
            if (cityId in cities) {
                continue
            }
            cities[cityId] = dist

            // PHP: if ($dist >= $maxDist) continue; (do not expand past the radius).
            if (dist >= maxDist) {
                continue
            }

            val city = cityConst.byId(cityId) ?: continue
            for (connCityId in city.path.keys) {
                if (connCityId in cities) {
                    continue
                }
                queue.addLast(intArrayOf(connCityId, dist + 1))
            }
        }

        return cities
    }

    /**
     * Port of PHP `searchDistance($from, $maxDist, $distForm=true)` — the RING form (`func.php:1929-1932`):
     * `distanceList[$dist][] = $cityID` keyed by hop-distance, with `unset($distanceList[0])` dropping the
     * origin ring. Within each ring the order is the BFS enqueue (SplQueue FIFO) order — the LAST-max-pop
     * tie-break in `findNextCapital` (process_war.php:833-837) depends on this ring-iteration order.
     *
     * Returns a `dist → [cityIds]` map (ascending dist; the origin's `dist=0` ring removed), the exact shape
     * `findNextCapital` walks outward.
     */
    fun searchDistanceRings(
        from: Int,
        maxDist: Int = 99,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Map<Int, List<Int>> {
        val cities = HashSet<Int>()
        val rings = LinkedHashMap<Int, MutableList<Int>>()
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(from, 0))

        while (queue.isNotEmpty()) {
            val (cityId, dist) = queue.removeFirst().let { it[0] to it[1] }
            if (cityId in cities) {
                continue
            }
            rings.getOrPut(dist) { mutableListOf() }.add(cityId)
            cities.add(cityId)

            if (dist >= maxDist) {
                continue
            }

            val city = cityConst.byId(cityId) ?: continue
            for (connCityId in city.path.keys) {
                if (connCityId in cities) {
                    continue
                }
                queue.addLast(intArrayOf(connCityId, dist + 1))
            }
        }

        // PHP `unset($distanceList[0])` — drop the origin ring.
        rings.remove(0)
        return rings
    }

    /**
     * The preloaded-distance set the C-PURE/C-DEST `nearCity` constraint key consumes.
     *
     * Returns the set of city ids within [radius] hops of [from], EXCLUDING [from] itself.
     * Derived from [searchDistance]: PHP `NearCity.test()` does
     * `key_exists($destCity, searchDistance($from, $arg, false))` where the dest is never the
     * origin (self-targets are rejected by notSameDestCity), so membership here is equivalent
     * to the PHP constraint's key check. For radius=1 this is exactly the direct path neighbours.
     */
    fun nearCity(
        from: Int,
        radius: Int,
        cityConst: CityConstVariant = CityConstRegistry.of(CityConstRegistry.DEFAULT_MAP_NAME),
    ): Set<Int> {
        val dist = searchDistance(from, radius, cityConst)
        return dist.keys.filterTo(LinkedHashSet()) { it != from }
    }
}
