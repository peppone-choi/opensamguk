package opensamguk.logic.world

import opensamguk.logic.domain.Diplomacy

/**
 * AREA B3 — SetNationFront (PHP `func_gamerule.php:45-102`).
 *
 * Recomputes the `front` flag (0/1/2/3) of every city a nation owns, from the SETTLED diplomacy state
 * (B2's Q9 transitions have already run) + the CityConst.path adjacency (the SAME machinery A2's supply
 * BFS uses). Runs LAST inside postUpdateMonthly (Q17), per active (level>0) nation in static-info order.
 * NON-randomized (no RNG lineage).
 *
 * The PHP builds three neighbor sets, each a UNION of `CityConst::byID(city).path` over a class of
 * cities, then applies four sequenced UPDATEs against the nation's OWN cities (front=0 reset, then
 * 1/2/3) — so on overlap the LAST write wins (front=3 > front=2 > front=1):
 *
 *   adj3 = ⋃ path(city)  for cities owned by a nation at diplomacy `state=0` (교전/at-war) with me;
 *   adj1 = ⋃ path(city)  for cities owned by a nation at `state=1 AND term<=5` (선포/war-declared);
 *   adj2 = ⋃ path(city)  for `nation=0` (neutral) cities — computed ONLY `if (!adj3 && !adj1)`
 *          (the comment: NPCs do not march into neutral land while at war; the gate reads ARRAY
 *           emptiness, not whether the union touched an owned city).
 *
 *   UPDATE city SET front=0 WHERE nation=me;
 *   front=1 WHERE nation=me AND city IN keys(adj1);
 *   front=2 WHERE nation=me AND city IN keys(adj2);
 *   front=3 WHERE nation=me AND city IN keys(adj3).
 *
 * Only cities the nation OWNS receive a front result; adj keys that aren't owned are filtered by the
 * `nation=me` clause of each UPDATE. The `term<=5` / `state` reads are the POST-Q9 settled values.
 */

/** A lightweight city projection for the front recompute (`city.city`/`city.nation`/`city.front`). */
data class FrontCity(val id: Int, val nationId: Int, val frontState: Int)

/**
 * The per-nation front recompute result: the target nation + the new `front` value of every city the
 * nation owns, keyed by city id (owned-city iteration order preserved as a LinkedHashMap). Empty when
 * `nationNo == 0` (the PHP `if (!$nationNo) return;` guard).
 */
data class NationFrontResult(val nationId: Int, val fronts: Map<Int, Int>)

/**
 * Port of `SetNationFront($nationNo)` (`func_gamerule.php:45-102`).
 *
 * @param nationNo the target nation id (`me`). `0` is the PHP early-return guard → empty result.
 * @param cities ALL cities (the PHP queries the live `city` table); we filter `nation=me` for the
 *   front writes and read every city's owner for the adjacency-source classes.
 * @param diplomacy ALL diplomacy rows; the PHP joins `diplomacy.you = city.nation` with `me=nationNo`,
 *   so a `state=0` / `state=1` row for `(me=nationNo, you=N)` makes EVERY city owned by nation N an
 *   adjacency source. The settled (post-Q9) `state`/`term` are read.
 * @param cityConst the active per-map CityConst variant; `byId(id).path.keys` = the neighbor ids
 *   (insertion order — the same anchor #3 as the supply BFS).
 */
fun setNationFront(
    nationNo: Int,
    cities: List<FrontCity>,
    diplomacy: List<Diplomacy>,
    cityConst: CityConstVariant,
): NationFrontResult {
    // PHP `if (!$nationNo) { return; }` — nation 0 (neutral / no nation) is a no-op.
    if (nationNo == 0) return NationFrontResult(nationNo, emptyMap())

    // The set of `you` nations that are at state=0 / state=1(term<=5) with me (post-Q9 settled rows).
    val atWarNations = HashSet<Int>()       // state=0 (교전) → adj3 source nations
    val declaredNations = HashSet<Int>()    // state=1 AND term<=5 (선포) → adj1 source nations
    for (d in diplomacy) {
        if (d.me != nationNo) continue
        if (d.state == 0) atWarNations.add(d.you)
        else if (d.state == 1 && d.term <= 5) declaredNations.add(d.you)
    }

    // Build each adjacency UNION over `CityConst::byID(city).path` keys. The PHP keys an associative
    // array by connected-city-id (later writes overwrite earlier ones — a SET) so a LinkedHashSet
    // matches the `array_keys` membership the `city IN %li` UPDATE uses.
    fun unionPathOf(sourceCities: List<FrontCity>): LinkedHashSet<Int> {
        val out = LinkedHashSet<Int>()
        for (c in sourceCities) {
            val node = cityConst.byId(c.id) ?: continue
            out.addAll(node.path.keys)
        }
        return out
    }

    // adj3 ← cities owned by an at-war (state=0) nation; adj1 ← cities owned by a declared-war nation.
    val adj3 = unionPathOf(cities.filter { it.nationId in atWarNations })
    val adj1 = unionPathOf(cities.filter { it.nationId in declaredNations })

    // adj2 ← neighbors of NEUTRAL (nation=0) cities, ONLY when neither adj3 nor adj1 exists (PHP :73).
    val adj2: LinkedHashSet<Int> =
        if (adj3.isEmpty() && adj1.isEmpty()) unionPathOf(cities.filter { it.nationId == 0 })
        else LinkedHashSet()

    // Apply the four sequenced UPDATEs over MY cities (nation=me). LAST WRITE WINS: 0 → 1 → 2 → 3.
    val fronts = LinkedHashMap<Int, Int>()
    for (c in cities) {
        if (c.nationId != nationNo) continue
        var front = 0                                   // UPDATE front=0 WHERE nation=me
        if (c.id in adj1) front = 1                     // front=1 WHERE nation=me AND city IN adj1
        if (c.id in adj2) front = 2                     // front=2 WHERE nation=me AND city IN adj2
        if (c.id in adj3) front = 3                     // front=3 WHERE nation=me AND city IN adj3
        fronts[c.id] = front
    }

    return NationFrontResult(nationNo, fronts)
}
