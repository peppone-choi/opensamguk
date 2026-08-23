package opensamguk.logic.world

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.util.phpRound

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

/**
 * The result of [applyCitySupply]: the mutated city + general snapshots (same iteration order as the
 * inputs), the neutralized (lost) city ids (first-seen = ascending city id), and the byte-exact 고립
 * global-history log lines (one per lost city, in lost-city iteration order).
 *
 * [lostCityIds] feeds the F3 delete/tombstone seam at integration time (the neutralized cities lose
 * their ownership); the pure core itself only computes the mutated entities + the logs.
 */
data class CitySupplyResult(
    val cities: List<City>,
    val generals: List<General>,
    val lostCityIds: List<Int>,
    val isolatedLogs: List<String>,
)

/**
 * Apply the full UpdateCitySupply persistence + decay + neutralize cascade (PHP `:60-131`), in the
 * EXACT PHP order:
 *   1. net supply state — `supply=1 WHERE nation=0` (neutral force-supplied), `supply=0 WHERE
 *      nation!=0`, then `supply=1 WHERE city IN supplied`. Net: nation-0 cities supplied; owned
 *      cities supplied iff in the BFS set.
 *   2. 10% city decay `WHERE supply=0`: pop/trust/agri/comm/secu/def/wall *0.9. INT columns
 *      round-half-away (phpRound — the MariaDB double→INT store); `trust` is double precision (V4
 *      type fix) so it keeps the fraction (NO round) — load-bearing for the trust<30 threshold.
 *   3. 5% general decay: unsupplied cities grouped by nation in FIRST-SEEN (ascending city id)
 *      order; generals `WHERE city IN cityIDList AND nation=nationID`: crew/atmos/train *0.95
 *      (all three are INT columns → phpRound). Per-general the result is independent of grouping,
 *      but the cityIDList membership + the nation match are the PHP filter.
 *   4. trust<30 neutralize: lostCities = unsupplied AND (POST-decay) trust<30 (the PHP `SELECT ...
 *      WHERE supply=0` runs AFTER step 2 → reads the DECAYED trust). Push the 고립 global log per
 *      lost city (F7 [HistoryTokens.isolatedCity], via `ActionLogger(0,0,year,month)`); reset
 *      generals `officer_level=1, officer_city=0 WHERE officer_city IN lostIds`; neutralize cities
 *      `nation=0, officer_set=0, conflict={}, term=0, front=0 WHERE city IN lostIds`.
 *
 * PORT NOTE (PHP frozen historical baseline (ADR-LITE-042; not current product authority) over the plan's "REUSE FoundingCascade"): the UpdateCitySupply
 * officer-reset (`officer_level=1, officer_city=0`) is UNCONDITIONAL on the matched generals (NO
 * `officer_level<12` guard, NO makelimit write) — distinct from FoundingCascade.demoteMember; and
 * the city neutralize ALSO sets `officer_set=0`/`term=0` which FoundingCascade.neutralizeCity does
 * NOT. Ported faithfully here rather than reusing the (different-shaped) 방랑 cascade helpers.
 */
fun applyCitySupply(
    cities: List<City>,
    generals: List<General>,
    capitals: List<SupplyCapital>,
    cityConst: CityConstVariant,
    year: Int,
    month: Int,
): CitySupplyResult {
    // The BFS reads ONLY owned cities (nation != 0), iterated in ASCENDING id order (anchor #1).
    val ownedCities = cities.filter { it.nationId != 0 }.map { SupplyCity(it.id, it.nationId) }
    val suppliedSet = computeSuppliedCities(ownedCities, capitals, cityConst)

    // Step 1 — net supply: nation==0 → supplied; nation!=0 → supplied iff in the BFS set.
    fun isSupplied(c: City): Boolean = if (c.nationId == 0) true else c.id in suppliedSet

    // Steps 1+2 over the cities (preserve input order). Unsupplied owned cities decay 10%.
    val decayedCities = cities.map { c ->
        val supplied = isSupplied(c)
        val supplyFlag = if (supplied) 1 else 0
        if (supplied) {
            c.copy(supplyState = supplyFlag)
        } else {
            // 10% decay (supply=0). INT columns phpRound(v*0.9); trust double *0.9 (no round).
            c.copy(
                supplyState = supplyFlag,
                population = phpRound(c.population * 0.9),
                trust = c.trust * 0.9,
                agriculture = phpRound(c.agriculture * 0.9),
                commerce = phpRound(c.commerce * 0.9),
                security = phpRound(c.security * 0.9),
                defense = phpRound(c.defense * 0.9),
                wall = phpRound(c.wall * 0.9),
            )
        }
    }

    // Step 3 — 5% general decay. A general decays iff its city is an unsupplied OWNED city of its
    // own nation (the PHP `city IN unsupplied(nation) AND nation=nationID` filter). Neutral (nation=0)
    // cities are force-supplied (step 1), so generals there never decay.
    val unsuppliedByCity = decayedCities.asSequence()
        .filter { it.supplyState == 0 } // includes only nation!=0 cities (nation=0 are supplied)
        .associateBy({ it.id }, { it.nationId })
    val decayedGenerals = generals.map { g ->
        val cityNation = unsuppliedByCity[g.cityId]
        if (cityNation != null && cityNation == g.nationId) {
            g.copy(
                crew = phpRound(g.crew * 0.95),
                atmos = phpRound(g.atmos * 0.95).toDouble(),
                train = phpRound(g.train * 0.95).toDouble(),
            )
        } else {
            g
        }
    }

    // Step 4 — trust<30 neutralize. lostCities = unsupplied (owned) AND POST-decay trust < 30.
    // First-seen order = ascending city id (the decayedCities preserve input order; anchor #1).
    val lostCities = decayedCities.filter { it.supplyState == 0 && it.trust < 30.0 }
    val lostCityIds = lostCities.map { it.id }
    val lostCitySet = lostCityIds.toSet()
    // The 고립 log uses the city's canonical name (PHP `city.name`); resolved from the active
    // CityConst (the byte-identical seed source) so the JosaUtil pick matches the golden.
    val isolatedLogs = lostCities.map { HistoryTokens.isolatedCity(cityConst.byId(it.id)?.name ?: "") }

    if (lostCitySet.isEmpty()) {
        return CitySupplyResult(decayedCities, decayedGenerals, lostCityIds, isolatedLogs)
    }

    // 4a — officer reset: officer_level=1, officer_city=0 WHERE officer_city IN lostIds (unconditional).
    val finalGenerals = decayedGenerals.map { g ->
        val officerCity = (g.meta["officer_city"] as? Number)?.toInt() ?: 0
        if (officerCity in lostCitySet) {
            g.copy(officerLevel = 1, meta = withMetaSet(g.meta, "officer_city", 0))
        } else {
            g
        }
    }

    // 4b — city neutralize: nation=0, officer_set=0, conflict={}, term=0, front=0 WHERE city IN lostIds.
    val finalCities = decayedCities.map { c ->
        if (c.id in lostCitySet) {
            val nextMeta = LinkedHashMap(c.meta)
            nextMeta.remove("conflict")   // conflict='{}' = key removed (delete-on-default jsonb)
            nextMeta["officer_set"] = 0
            nextMeta["term"] = 0
            c.copy(nationId = 0, frontState = 0, meta = nextMeta)
        } else {
            c
        }
    }

    return CitySupplyResult(finalCities, finalGenerals, lostCityIds, isolatedLogs)
}

/** Set a single meta key (insertion-order-preserving), like [opensamguk.logic.domain.withMeta]. */
private fun withMetaSet(meta: Map<String, Any?>, key: String, value: Any?): Map<String, Any?> {
    val next = LinkedHashMap(meta)
    next[key] = value
    return next
}

/** The seam [UpdateCitySupplyAction] uses to reach the world snapshot + apply the result. */
interface UpdateCitySupplyContext : EventActionContext {
    fun cities(): List<City>
    fun generals(): List<General>
    fun capitals(): List<SupplyCapital>
    fun cityConst(): CityConstVariant
    fun year(): Int
    fun month(): Int
    /** Apply the mutated cities/generals to the world and push the isolated logs. */
    fun applyCitySupply(result: CitySupplyResult)
}

/**
 * `UpdateCitySupply` (PHP `Event/Action/UpdateCitySupply.php:11-133`): recompute city supply BFS,
 * decay unsupplied cities (10%) and generals (5%), neutralize cities whose post-decay trust < 30.
 */
class UpdateCitySupplyAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val uctx = ctx as? UpdateCitySupplyContext
            ?: throw IllegalArgumentException("UpdateCitySupply requires UpdateCitySupplyContext")
        val result = applyCitySupply(
            uctx.cities(),
            uctx.generals(),
            uctx.capitals(),
            uctx.cityConst(),
            uctx.year(),
            uctx.month(),
        )
        uctx.applyCitySupply(result)
    }

    companion object {
        const val NAME = "UpdateCitySupply"

        /** Register the `UpdateCitySupply` leaf into the F2-owned factory by name (plan §append protocol). */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { UpdateCitySupplyAction() }
    }
}
