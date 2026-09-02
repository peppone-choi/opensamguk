package opensamguk.logic.world

import opensamguk.common.constants.CityConst.RawCity
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.log.HistoryTokens
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AREA A2 / Task SU2 — supply persistence + 10%/5% decay + trust<30 neutralize cascade.
 *
 * Port target: PHP UpdateCitySupply.php:60-131.
 *   - net supply: supply=1 WHERE nation=0; supply=0 WHERE nation!=0; supply=1 WHERE city IN supplied.
 *   - 10% city decay WHERE supply=0: pop/trust/agri/comm/secu/def/wall *0.9.
 *   - 5% general decay: unsupplied cities grouped by nation (first-seen = ascending city id):
 *     crew/atmos/train *0.95.
 *   - trust<30 neutralize: lostCities = unsupplied AND (POST-decay) trust<30; 고립 global log per city;
 *     general officer_level=1, officer_city=0 WHERE officer_city IN lostIds; city neutralize
 *     (nation=0, officer_set=0, conflict={}, term=0, front=0) WHERE city IN lostIds.
 *
 * DECAY ROUNDING (the #1 numeric risk): INT columns (pop/agri/comm/secu/def/wall + general
 * crew/atmos/train) = MariaDB double→INT store = round-half-away (phpRound). `trust` = double
 * precision (V4 type fix) keeps the fraction (NO round) so the trust<30 threshold survives flush.
 */
class UpdateCitySupplyLossTest {

    private fun lineConst(): CityConstVariant {
        val rows = listOf(
            RawCity(1, "A", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B")),
            RawCity(2, "B", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("A", "C")),
            RawCity(3, "C", "특", 100, 1, 1, 1, 1, 1, "하북", 0, 0, listOf("B")),
        )
        return InitCityOverrideVariant("line3", rows)
    }

    private fun city(
        id: Int, nationId: Int, trust: Double = 100.0, pop: Int = 1000, agri: Int = 200,
        comm: Int = 200, secu: Int = 200, def: Int = 200, wall: Int = 200, meta: Map<String, Any?> = linkedMapOf(),
    ) = City(
        id = id, nationId = nationId, level = 8, commerce = comm, commerceMax = 1000,
        agriculture = agri, agricultureMax = 1000, supplyState = 0, frontState = 1, trust = trust,
        security = secu, securityMax = 1000, defense = def, defenseMax = 1000, wall = wall, wallMax = 1000,
        population = pop, populationMax = 100000, meta = LinkedHashMap(meta),
    )

    private fun general(
        id: Int, nationId: Int, cityId: Int, officerLevel: Int = 4, crew: Int = 1000,
        atmos: Double = 100.0, train: Double = 100.0, officerCity: Int = 0,
    ) = General(
        id = id, nationId = nationId, cityId = cityId, leadership = 50, strength = 50, intel = 50,
        injury = 0, experience = 0.0, dedication = 0.0, officerLevel = officerLevel, gold = 0, rice = 0,
        crew = crew, train = train, atmos = atmos, meta = linkedMapOf<String, Any?>("officer_city" to officerCity),
    )

    @Test
    fun `neutral nation-0 cities are force-supplied and excluded from decay`() {
        val result = applyCitySupply(
            cities = listOf(city(1, 0, trust = 5.0), city(2, 1)),
            generals = emptyList(),
            capitals = listOf(SupplyCapital(2, 1)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        val c1 = result.cities.first { it.id == 1 }
        assertEquals(1, c1.supplyState, "nation=0 city force-supplied")
        // a force-supplied neutral city does NOT decay even with trust<30 (it is excluded from BFS+loss)
        assertEquals(5.0, c1.trust, 1e-9)
        assertTrue(result.lostCityIds.isEmpty(), "neutral cities never neutralized")
    }

    @Test
    fun `supplied owned city keeps its stats and unsupplied owned city decays 10 percent`() {
        // nation 1 owns 1 (capital, supplied) and 3 (isolated by nation-2 city 2 between them).
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(2, 2), city(3, 1)),
            generals = emptyList(),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(2, 2)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        val c1 = result.cities.first { it.id == 1 }
        val c3 = result.cities.first { it.id == 3 }
        assertEquals(1, c1.supplyState)
        assertEquals(0, c3.supplyState, "city 3 cut off by nation-2 border")
        // c1 supplied → unchanged
        assertEquals(1000, c1.population); assertEquals(100.0, c1.trust, 1e-9)
        // c3 unsupplied → INT columns phpRound(v*0.9); trust double *0.9 (no round)
        assertEquals(phpRound(1000 * 0.9), c3.population)
        assertEquals(phpRound(200 * 0.9), c3.agriculture)
        assertEquals(phpRound(200 * 0.9), c3.commerce)
        assertEquals(phpRound(200 * 0.9), c3.security)
        assertEquals(phpRound(200 * 0.9), c3.defense)
        assertEquals(phpRound(200 * 0.9), c3.wall)
        assertEquals(100.0 * 0.9, c3.trust, 1e-9)
    }

    @Test
    fun `spatial non-seat corridor supplies a city that the legacy city graph cannot reach`() {
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(3, 1)),
            generals = emptyList(),
            capitals = listOf(SupplyCapital(1, 1)),
            cityConst = lineConst(),
            year = 200,
            month = 1,
            spatialSupplyNetwork = SpatialSupplyNetwork(
                provinceOwners = intArrayOf(1, 1, 1),
                provinceAdjacency = listOf(intArrayOf(1), intArrayOf(0, 2), intArrayOf(1)),
                cityProvinceIndices = mapOf(1 to 0, 3 to 2),
            ),
        )

        val remote = result.cities.first { it.id == 3 }
        assertEquals(1, remote.supplyState)
        assertEquals(1000, remote.population)
        assertEquals(100.0, remote.trust, 1e-9)
    }

    @Test
    fun `unsupplied general decays crew atmos train 5 percent (phpRound, int columns)`() {
        // nation 1: capital 1 supplied; city 3 isolated. general in city 3 decays.
        val gSupplied = general(10, 1, cityId = 1, crew = 1000, atmos = 100.0, train = 100.0)
        val gUnsupplied = general(11, 1, cityId = 3, crew = 1000, atmos = 100.0, train = 100.0)
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(2, 2), city(3, 1)),
            generals = listOf(gSupplied, gUnsupplied),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(2, 2)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        val s = result.generals.first { it.id == 10 }
        val u = result.generals.first { it.id == 11 }
        assertEquals(1000, s.crew); assertEquals(100.0, s.atmos, 1e-9) // supplied → untouched
        assertEquals(phpRound(1000 * 0.95), u.crew)
        assertEquals(phpRound(100.0 * 0.95).toDouble(), u.atmos, 1e-9)
        assertEquals(phpRound(100.0 * 0.95).toDouble(), u.train, 1e-9)
    }

    @Test
    fun `trust below 30 after decay neutralizes the city - 고립 log byte-match`() {
        // city 3 isolated, trust 30 BEFORE decay → 27 AFTER decay (<30) → neutralized.
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(2, 2), city(3, 1, trust = 30.0)),
            generals = emptyList(),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(2, 2)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        assertEquals(listOf(3), result.lostCityIds)
        val c3 = result.cities.first { it.id == 3 }
        assertEquals(0, c3.nationId, "neutralized → nation 0")
        assertEquals(0, c3.frontState, "front reset")
        assertNull(c3.meta["conflict"], "conflict reset (= {} = key removed)")
        assertEquals(0, c3.meta["officer_set"], "officer_set=0")
        assertEquals(0, c3.meta["term"], "term=0")
        // 고립 log byte-match against the F7 token (uses post-name JosaUtil pick)
        assertEquals(listOf(HistoryTokens.isolatedCity("C")), result.isolatedLogs)
    }

    @Test
    fun `trust exactly 30 after decay is NOT neutralized (threshold is strictly less than 30)`() {
        // trust must be >= 33.34 BEFORE decay to stay >= 30 AFTER. Use 40 → 36 (>=30) → kept.
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(2, 2), city(3, 1, trust = 40.0)),
            generals = emptyList(),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(2, 2)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        assertTrue(result.lostCityIds.isEmpty(), "post-decay trust 36 >= 30 → kept")
        val c3 = result.cities.first { it.id == 3 }
        assertEquals(1, c3.nationId, "still owned")
        assertEquals(36.0, c3.trust, 1e-9)
    }

    @Test
    fun `lost city officers are reset (officer_level=1, officer_city=0 WHERE officer_city IN lostIds)`() {
        // general 20 holds an office in city 3 (officer_city=3). When city 3 is lost, reset.
        val officer = general(20, 1, cityId = 1, officerLevel = 11, officerCity = 3)
        // a general whose officer_city is NOT lost stays untouched
        val keep = general(21, 1, cityId = 1, officerLevel = 11, officerCity = 1)
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(2, 2), city(3, 1, trust = 10.0)),
            generals = listOf(officer, keep),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(2, 2)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        assertEquals(listOf(3), result.lostCityIds)
        val reset = result.generals.first { it.id == 20 }
        assertEquals(1, reset.officerLevel, "officer_level reset to 1")
        assertEquals(0, (reset.meta["officer_city"] as Number).toInt(), "officer_city reset to 0")
        val unchanged = result.generals.first { it.id == 21 }
        assertEquals(11, unchanged.officerLevel, "officer in a kept city is untouched")
    }

    @Test
    fun `general 5 percent decay groups unsupplied cities by ascending city id (first-seen order)`() {
        // two unsupplied cities of the SAME nation; both generals decay identically regardless of group
        // ordering, but the result is byte-identical to per-city processing.
        val gA = general(30, 1, cityId = 3, crew = 1000)
        val result = applyCitySupply(
            cities = listOf(city(1, 1), city(2, 2), city(3, 1)),
            generals = listOf(gA),
            capitals = listOf(SupplyCapital(1, 1), SupplyCapital(2, 2)),
            cityConst = lineConst(), year = 200, month = 1,
        )
        assertEquals(phpRound(1000 * 0.95), result.generals.first { it.id == 30 }.crew)
    }
}
