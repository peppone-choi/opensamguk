package opensamguk.logic.world

import opensamguk.common.constants.CityConst.RawCity
import opensamguk.logic.domain.Diplomacy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AREA B3 / Task FR1B — SetNationFront (front 0/1/2/3 via settled diplomacy state/term + CityConst.path).
 *
 * Port target: PHP `func_gamerule.php:45-102` (SetNationFront). Runs LAST in postUpdateMonthly (Q17),
 * AFTER B2's Q9 diplomacy settlement; shares the CityConst.path adjacency machinery with A2's BFS.
 *
 * The EXACT PHP overwrite order (source-verified — LAST WRITE WINS via the sequenced UPDATEs):
 *   adj3 = union of `CityConst::byID(city).path` for cities owned by a nation at diplomacy `state=0`
 *          (교전/at-war) with me;
 *   adj1 = same for `state=1 AND term<=5` (선포/war-declared);
 *   adj2 = same for `nation=0` (neutral) cities — computed ONLY `if (!adj3 && !adj1)`;
 *   then UPDATE my cities: front=0 (ALL) → front=1 (in adj1) → front=2 (in adj2) → front=3 (in adj3).
 * Only MY cities (nation=nationNo) receive the front write; adj keys that aren't my cities are ignored.
 */
class SetNationFrontTest {

    // A 6-city line: 1—2—3—4—5—6 (symmetric adjacency, path insertion-ordered).
    private fun lineConst(): CityConstVariant {
        val rows = listOf(
            RawCity(1, "A", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B")),
            RawCity(2, "B", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("A", "C")),
            RawCity(3, "C", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B", "D")),
            RawCity(4, "D", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("C", "E")),
            RawCity(5, "E", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("D", "F")),
            RawCity(6, "F", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("E")),
        )
        return InitCityOverrideVariant("line6", rows)
    }

    private fun city(id: Int, nationId: Int, front: Int = 9): FrontCity =
        FrontCity(id = id, nationId = nationId, frontState = front)

    @Test
    fun `peacetime - adj2 neutral expansion on the frontier of own cities`() {
        // nation 1 owns cities 1,2,3; city 4 is neutral (nation=0); no diplomacy rows.
        // adj3/adj1 empty → adj2 computed from neighbors of nation=0 cities.
        // city 4 (neutral) path = {3,5}; only my city 3 is in adj2 → front=2 for city 3, others 0.
        val cities = listOf(
            city(1, 1), city(2, 1), city(3, 1), city(4, 0), city(5, 0), city(6, 0),
        )
        val result = setNationFront(
            nationNo = 1,
            cities = cities,
            diplomacy = emptyList(),
            cityConst = lineConst(),
        )
        val fronts = result.fronts
        assertEquals(0, fronts.getValue(1))
        assertEquals(0, fronts.getValue(2))
        assertEquals(2, fronts.getValue(3)) // neighbor of neutral city 4
        // Only my cities (nation=1) get a front result.
        assertEquals(setOf(1, 2, 3), fronts.keys)
    }

    @Test
    fun `at-war state=0 - adj3 front=3 on my cities neighboring the enemy`() {
        // nation 1 owns 1,2,3; nation 2 owns 4,5,6; diplomacy me=1,you=2,state=0 (교전).
        // adj3 = neighbors of nation-2 cities {4,5,6}: 4→{3,5}, 5→{4,6}, 6→{5} = {3,4,5,6}.
        // My cities in adj3 = {3} → front=3. adj2 NOT computed (adj3 present).
        val cities = listOf(
            city(1, 1), city(2, 1), city(3, 1), city(4, 2), city(5, 2), city(6, 2),
        )
        val result = setNationFront(
            nationNo = 1,
            cities = cities,
            diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 0, term = 8)),
            cityConst = lineConst(),
        )
        val fronts = result.fronts
        assertEquals(0, fronts.getValue(1))
        assertEquals(0, fronts.getValue(2))
        assertEquals(3, fronts.getValue(3))
    }

    @Test
    fun `declared-war state=1 term less-equal 5 - adj1 front=1`() {
        // nation 1 owns 1,2,3; nation 2 owns 4,5,6; diplomacy state=1 term=3 (선포, term<=5).
        // adj1 = neighbors of nation-2 cities = {3,4,5,6}; my cities in adj1 = {3} → front=1.
        val cities = listOf(
            city(1, 1), city(2, 1), city(3, 1), city(4, 2), city(5, 2), city(6, 2),
        )
        val result = setNationFront(
            nationNo = 1,
            cities = cities,
            diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 1, term = 3)),
            cityConst = lineConst(),
        )
        assertEquals(1, result.fronts.getValue(3))
        assertEquals(0, result.fronts.getValue(2))
    }

    @Test
    fun `declared-war term greater than 5 is ignored (no adj1)`() {
        // state=1 but term=6 (>5) → adj1 empty. adj3 empty too → adj2 (no neutral cities) empty.
        // All my cities reset to front=0.
        val cities = listOf(
            city(1, 1), city(2, 1), city(3, 1), city(4, 2), city(5, 2), city(6, 2),
        )
        val result = setNationFront(
            nationNo = 1,
            cities = cities,
            diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 1, term = 6)),
            cityConst = lineConst(),
        )
        assertEquals(0, result.fronts.getValue(3))
        assertEquals(0, result.fronts.getValue(1))
        assertEquals(0, result.fronts.getValue(2))
    }

    @Test
    fun `overlap - a city in BOTH adj1 and adj3 ends front=3 (last write wins)`() {
        // My city 3 neighbors BOTH a state=1 enemy (nation 2, city 4) AND a state=0 enemy (nation 3, city 2).
        // Layout: nation 3 owns city 2, nation 1 owns cities 1,3, nation 2 owns cities 4,5,6.
        //   city 4 (state=0? no — set nation 2 = state=1; nation 3 = state=0):
        // Use: nation 1 owns {3}; nation 2 owns {4} state=1 (선포); nation 3 owns {2} state=0 (교전).
        //   adj1 = neighbors of nation-2 city 4 = {3,5}; my city 3 ∈ adj1.
        //   adj3 = neighbors of nation-3 city 2 = {1,3}; my city 3 ∈ adj3.
        //   front sequence: 3→1 (adj1) then 3→3 (adj3) ⇒ city 3 ends front=3.
        val cities = listOf(
            city(1, 0), city(2, 3), city(3, 1), city(4, 2), city(5, 0), city(6, 0),
        )
        val result = setNationFront(
            nationNo = 1,
            cities = cities,
            diplomacy = listOf(
                Diplomacy(me = 1, you = 2, state = 1, term = 2), // 선포 → adj1
                Diplomacy(me = 1, you = 3, state = 0, term = 9), // 교전 → adj3
            ),
            cityConst = lineConst(),
        )
        assertEquals(3, result.fronts.getValue(3)) // last write (adj3) wins over adj1
        assertEquals(setOf(3), result.fronts.keys) // nation 1 owns only city 3
    }

    @Test
    fun `adj2 NOT computed when adj1 present (NPCs do not march into neutral land during war)`() {
        // nation 1 owns 2,3; nation 2 owns 5 (state=1 선포); city 4 is neutral (nation=0).
        // adj1 present (neighbor of city 5 = {4,6}; none of MY cities) → so adj1 array non-empty
        // even though it touches no owned city; adj2 is therefore SUPPRESSED (the !adj3 && !adj1 gate
        // reads array emptiness, not whether it hit an owned city).
        val cities = listOf(
            city(1, 0), city(2, 1), city(3, 1), city(4, 0), city(5, 2), city(6, 0),
        )
        val result = setNationFront(
            nationNo = 1,
            cities = cities,
            diplomacy = listOf(Diplomacy(me = 1, you = 2, state = 1, term = 4)),
            cityConst = lineConst(),
        )
        // adj1 = {4,6} (none owned by me) → no my-city front=1; adj2 suppressed → city 3 (neighbor of
        // neutral 4) stays front=0 because adj1 is non-empty.
        assertEquals(0, result.fronts.getValue(2))
        assertEquals(0, result.fronts.getValue(3))
    }

    @Test
    fun `empty nation number returns no fronts (PHP guard)`() {
        val result = setNationFront(
            nationNo = 0,
            cities = listOf(city(1, 0)),
            diplomacy = emptyList(),
            cityConst = lineConst(),
        )
        assertEquals(emptyMap(), result.fronts)
    }
}
