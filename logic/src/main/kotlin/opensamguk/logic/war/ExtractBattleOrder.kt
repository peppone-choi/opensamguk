package opensamguk.logic.war

import kotlin.math.pow

/**
 * BO1 — port target = PHP `process_war.php:192-226` (`extractBattleOrder`) + the defender candidate
 * ordering (`:36-61`). NO RNG draws — pure arithmetic + a stable sort.
 *
 * `extractBattleOrder(defender, attacker)` returns the FLOAT battle-order weight (`process_war.php:192`):
 *  - [WarUnitCity] branch: requires the attacker to be a [WarUnitGeneral]; returns its
 *    `cityBattleOrder()` cross-stat fold (`:199`) — a non-[WarUnitGeneral] attacker yields 0.
 *  - [WarUnitGeneral] branch — the FOUR ZERO gates (`:203-218`), then
 *    `totalStat = (realStat + fullStat)/2` (`:220-222`) and
 *    `totalCrew = crew/1e6 * (train*atmos)^1.5` (`:224`); return `totalStat + totalCrew/100` (`:225`).
 *
 * [orderDefenders] builds the battle queue (`process_war.php:36-61`): the general candidates are explicitly
 * `sortedBy { it.no }` (ascending PK) BEFORE the usort — the PHP `SELECT … FROM general` had no ORDER BY and
 * returned PK order incidentally; V1 Postgres does NOT, so the sort is PINNED here (PR-4). Candidates with
 * order<=0 are filtered, the city is appended only when the list is non-empty AND its order>0, and the final
 * sort is a STABLE DESC-by-float with a documented ascending-`no` secondary key (PHP 8.0+ usort is
 * unconditionally stable; the secondary key is asserted directly, not deferred to a golden).
 */
fun extractBattleOrder(defender: WarUnit, attacker: WarUnit): Double {
    if (defender is WarUnitCity) {
        // PHP :194-200 — the city order requires a general attacker; else 0.
        if (attacker !is WarUnitGeneral) return 0.0
        return attacker.cityBattleOrder()
    }

    val g = defender as WarUnitGeneral

    // --- the FOUR ZERO gates (PHP :203-218) ---
    if (g.getCrew() == 0) return 0.0
    if (g.getRice() <= g.getCrew() / 100.0) return 0.0
    val defenceTrain = g.getDefenceTrain()
    if (g.getTrain() < defenceTrain) return 0.0
    if (g.getAtmos() < defenceTrain) return 0.0

    // --- totalStat = (real + full)/2 (PHP :220-222) ---
    val realStat = g.getRealStatSum()
    val fullStat = g.getFullStatSum()
    val totalStat = (realStat + fullStat) / 2

    // --- totalCrew = crew/1e6 * (train*atmos)^1.5 (PHP :224) ---
    val totalCrew = g.getCrew() / 1_000_000.0 * (g.getTrain() * g.getAtmos()).pow(1.5)

    return totalStat + totalCrew / 100
}

/**
 * Build the ordered defender battle queue (PHP `process_war.php:36-61`).
 *
 * @param candidates the defender-CITY's same-nation generals (NOT pre-sorted — this fn pins the order).
 * @param attacker the attacking [WarUnitGeneral] (the usort comparator's reference unit).
 * @param city the defender city; appended LAST as the siege defender when the general list is non-empty AND
 *   its [extractBattleOrder] > 0 (`:55-57`). `null` ⇒ no city append (used by the ordering unit tests).
 */
fun orderDefenders(
    candidates: List<WarUnitGeneral>,
    attacker: WarUnitGeneral,
    city: WarUnitCity?,
): List<WarUnit> {
    // PHP SELECT had no ORDER BY → pin ascending PK BEFORE filtering/usort (PR-4); filter order<=0 (:48-52).
    val defenders: MutableList<WarUnit> = candidates
        .sortedBy { it.no }
        .filter { extractBattleOrder(it, attacker) > 0 }
        .toMutableList()

    // PHP :55-57 — append the city only when the general list is non-empty AND its order>0.
    if (defenders.isNotEmpty() && city != null && extractBattleOrder(city, attacker) > 0) {
        defenders.add(city)
    }

    // PHP :59-61 usort DESC by float order — PHP 8.0+ usort is STABLE, so pin the ascending-`no` secondary
    // key explicitly (the city sorts after equal-order generals via a max-`no` sentinel — it is only ever
    // appended once and ties with a general are not a parity concern, but the key is total/deterministic).
    return defenders.sortedWith(
        compareByDescending<WarUnit> { extractBattleOrder(it, attacker) }
            .thenBy { if (it is WarUnitGeneral) it.no else Int.MAX_VALUE },
    )
}
