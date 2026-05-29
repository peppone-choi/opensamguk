package opensamguk.logic.world

/**
 * AREA A2 — UpdateCitySupply (PHP `Event/Action/UpdateCitySupply.php:11-133`).
 *
 * The DETERMINISTIC multi-source FIFO BFS that recomputes city supply each month + the unsupplied
 * 10%/5% decay + the trust<30 neutralization cascade. NON-randomized (no RNG lineage). Pure logic
 * core (the `randomizeCityTradeRate` precedent): the eventual `UpdateCitySupply` event leaf wraps
 * this body against the live world snapshot + the F2 dispatch context.
 *
 * Three DETERMINISM ANCHORS (PHP has no `ORDER BY` → relies on PK / iteration order):
 *   #1 the owned-city map is built in ASCENDING city id (PK) order;
 *   #2 capitals are seeded in ASCENDING nation id order;
 *   #3 neighbors are visited in `CityConst::byID(id)->path` INSERTION order (NOT sorted).
 *
 * SU1 supplies the BFS ([computeSuppliedCities] / [computeSuppliedCitiesOrdered]); SU2 adds the
 * persistence + decay + neutralize cascade ([applyCitySupply]).
 */

/** One owned city (PHP `SELECT city,nation FROM city WHERE nation!=0`). */
data class SupplyCity(val id: Int, val nationId: Int)

/** One nation capital seed (PHP `SELECT capital,nation FROM nation WHERE level>0`). */
data class SupplyCapital(val capitalCityId: Int, val nationId: Int)

/**
 * Multi-source FIFO BFS over the active CityConst.path adjacency (PHP `UpdateCitySupply.php:17-58`).
 *
 * @param cities owned cities (nation != 0); the caller supplies them in ASCENDING id order (anchor #1).
 * @param capitals capitals of nations with `level > 0`; the caller supplies them in ASCENDING nation
 *   id order (anchor #2). The `level > 0` guard is applied by the caller (wandering forces have no
 *   supplied capital).
 * @param cityConst the active per-map CityConst variant (F6); `byId(id).path.keys` is the neighbor
 *   order (anchor #3 — insertion order, NOT sorted).
 * @return the set of supplied city ids, in BFS discovery (enqueue) order.
 */
fun computeSuppliedCities(
    cities: List<SupplyCity>,
    capitals: List<SupplyCapital>,
    cityConst: CityConstVariant,
): Set<Int> = computeSuppliedCitiesOrdered(cities, capitals, cityConst).toSet()

/**
 * Same BFS as [computeSuppliedCities] but returns the discovery order as a LIST so the dequeue
 * sequence (FIFO not DFS; multi-source seeding order) is assertable.
 */
fun computeSuppliedCitiesOrdered(
    cities: List<SupplyCity>,
    capitals: List<SupplyCapital>,
    cityConst: CityConstVariant,
): List<Int> {
    // cities[id] = {nation, supply=false} in caller-supplied (PK ascending) order — anchor #1.
    val ownedNation = LinkedHashMap<Int, Int>()
    for (c in cities) ownedNation[c.id] = c.nationId

    val supplied = LinkedHashSet<Int>() // discovery order = enqueue order
    val queue = ArrayDeque<Int>()       // SplQueue == FIFO (addLast / removeFirst)

    // Seed from each valid capital (anchor #2 ordering is the caller's `capitals` list order).
    for (cap in capitals) {
        val cityNation = ownedNation[cap.capitalCityId] ?: continue // capital absent (not owned)
        if (cap.nationId != cityNation) continue                    // capital captured (nation mismatch)
        if (cap.capitalCityId in supplied) continue                 // already seeded
        supplied.add(cap.capitalCityId)
        queue.addLast(cap.capitalCityId)
    }

    // PROPAGATE FIFO. Neighbors via path.keys (anchor #3 — insertion order). Supply does not cross
    // borders (neighbor nation must match the dequeued city's nation) and never re-supplies.
    while (queue.isNotEmpty()) {
        val cityId = queue.removeFirst()
        val cityNation = ownedNation.getValue(cityId)
        val node = cityConst.byId(cityId) ?: continue
        for (connId in node.path.keys) {
            val connNation = ownedNation[connId] ?: continue // neighbor not owned
            if (connNation != cityNation) continue           // border (nation mismatch)
            if (connId in supplied) continue                 // already supplied
            supplied.add(connId)
            queue.addLast(connId)
        }
    }

    return supplied.toList()
}
