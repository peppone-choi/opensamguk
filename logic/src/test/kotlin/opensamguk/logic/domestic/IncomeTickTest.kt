package opensamguk.logic.domestic

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port-faithful tests for the INCOME-TICK helpers (IT3) — research Unit 12.
 *
 * PHP grand truth (func_time_event.php):
 *   - calcCityGoldIncome  :88-104  — supply==0→0 ; trustRatio=trust/200+0.5 ;
 *       income = pop*comm/comm_max*trustRatio/30 ; *= 1+secu/secu_max/10 ; *= pow(1.05, officerCnt) ;
 *       if capital *= 1+(1/3/nationLevel) ; THEN Util::round(nationType.onCalcNationalIncome('gold', income)).
 *   - calcCityRiceIncome  :106-122 — same shape with agri ; income type 'rice'.
 *   - calcCityWallRiceIncome :124-139 — wall = def*wall/wall_max/3 ; *= 1+secu/secu_max/10 ;
 *       *= pow(1.05, officerCnt) ; if capital *= 1+1/(3*nationLevel) ; THEN Util::round(...'rice').
 *   - getGoldIncome/getRiceIncome/getWallIncome :141-237 — SUM the per-city ROUNDED ints, THEN
 *       *= (taxRate/20) with NO final round (returns float).
 *   - getOutcome :239-249 — sum getBill(dedication); Util::round(sum * billRate / 100).
 *
 * The income fold runs over the NATION-TYPE source ONLY (GeneralActionPipeline.nationIncomeFold), never
 * the full general stack. Util::round = phpRound (half-away-from-zero). The capital factor is
 * 1 + 1/(3*nationLevel) for ALL THREE (PHP `1/3/$nationLevel` == `(1/3)/level` == `1/(3*level)`).
 */
class IncomeTickTest {

    private val pipeline = GeneralActionPipeline(emptyList())

    /** A nation-type income module that scales 'gold' by 1.1 (to assert the income fold is applied). */
    private val goldBuffNationType = object : GeneralActionModule {
        override fun onCalcNationalIncome(general: General, type: String, value: Double): Double =
            if (type == "gold") value * 1.1 else value
    }

    private fun city(
        supply: Int = 1, trust: Double = 100.0,
        pop: Int = 30000, comm: Int = 1000, commMax: Int = 2000,
        agri: Int = 1000, agriMax: Int = 2000,
        secu: Int = 1000, secuMax: Int = 2000,
        def: Int = 1000, wall: Int = 1000, wallMax: Int = 2000,
    ) = City(
        id = 1, nationId = 1, level = 5,
        commerce = comm, commerceMax = commMax,
        agriculture = agri, agricultureMax = agriMax,
        supplyState = supply, frontState = 0, trust = trust,
        security = secu, securityMax = secuMax,
        defense = def, wall = wall, wallMax = wallMax,
        population = pop, populationMax = 100000,
    )

    @Test
    fun `unsupplied city yields zero income for all three streams`() {
        val c = city(supply = 0)
        assertEquals(0, calcCityGoldIncome(c, 0, false, 5, null, pipeline))
        assertEquals(0, calcCityRiceIncome(c, 0, false, 5, null, pipeline))
        assertEquals(0, calcCityWallRiceIncome(c, 0, false, 5, null, pipeline))
    }

    @Test
    fun `gold income matches the PHP per-city formula then phpRound`() {
        val c = city()
        // trustRatio = 100/200 + 0.5 = 1.0
        var v = c.population.toDouble() * c.commerce / c.commerceMax * 1.0 / 30.0
        v *= 1 + c.security.toDouble() / c.securityMax / 10
        v *= 1.05.pow(0)
        val expected = phpRound(v)
        assertEquals(expected, calcCityGoldIncome(c, 0, false, 5, null, pipeline))
    }

    @Test
    fun `rice income uses agriculture not commerce`() {
        val c = city(comm = 1, commMax = 1000, agri = 1500, agriMax = 2000)
        var v = c.population.toDouble() * c.agriculture / c.agricultureMax * 1.0 / 30.0
        v *= 1 + c.security.toDouble() / c.securityMax / 10
        assertEquals(phpRound(v), calcCityRiceIncome(c, 0, false, 5, null, pipeline))
    }

    @Test
    fun `wall income uses def-wall over wall_max over 3`() {
        val c = city()
        var v = c.defense.toDouble() * c.wall / c.wallMax / 3
        v *= 1 + c.security.toDouble() / c.securityMax / 10
        assertEquals(phpRound(v), calcCityWallRiceIncome(c, 0, false, 5, null, pipeline))
    }

    @Test
    fun `officer count compounds 1_05 per officer`() {
        val c = city()
        val with0 = calcCityGoldIncome(c, 0, false, 5, null, pipeline)
        val with3 = calcCityGoldIncome(c, 3, false, 5, null, pipeline)
        // with3 reflects pow(1.05, 3) over the with-0 pre-round base
        var v = c.population.toDouble() * c.commerce / c.commerceMax * 1.0 / 30.0
        v *= 1 + c.security.toDouble() / c.securityMax / 10
        v *= 1.05.pow(3)
        assertEquals(phpRound(v), with3)
        assert(with3 > with0)
    }

    @Test
    fun `capital factor is 1 plus 1 over 3 times nationLevel for all three streams`() {
        val c = city()
        // gold capital
        var g = c.population.toDouble() * c.commerce / c.commerceMax * 1.0 / 30.0
        g *= 1 + c.security.toDouble() / c.securityMax / 10
        g *= 1 + (1.0 / 3.0 / 5.0) // == 1 + 1/(3*5)
        assertEquals(phpRound(g), calcCityGoldIncome(c, 0, true, 5, null, pipeline))
        // wall capital uses 1 + 1/(3*level) — same value
        var w = c.defense.toDouble() * c.wall / c.wallMax / 3
        w *= 1 + c.security.toDouble() / c.securityMax / 10
        w *= 1 + 1.0 / (3.0 * 5.0)
        assertEquals(phpRound(w), calcCityWallRiceIncome(c, 0, true, 5, null, pipeline))
    }

    @Test
    fun `income fold applies the nation-type source only`() {
        val c = city()
        var v = c.population.toDouble() * c.commerce / c.commerceMax * 1.0 / 30.0
        v *= 1 + c.security.toDouble() / c.securityMax / 10
        // gold fold *1.1 BEFORE the round
        assertEquals(phpRound(v * 1.1), calcCityGoldIncome(c, 0, false, 5, goldBuffNationType, pipeline))
        // rice is untouched by the gold-only buff
        var r = c.population.toDouble() * c.agriculture / c.agricultureMax * 1.0 / 30.0
        r *= 1 + c.security.toDouble() / c.securityMax / 10
        assertEquals(phpRound(r), calcCityRiceIncome(c, 0, false, 5, goldBuffNationType, pipeline))
    }

    @Test
    fun `getGoldIncome sums rounded per-city ints then scales by taxRate over 20 with no final round`() {
        val cities = listOf(city(), city(comm = 1500, commMax = 2000))
        val capitalId = cities.first().id
        val perCity = cities.map { calcCityGoldIncome(it, 0, it.id == capitalId, 5, null, pipeline) }
        val expected = perCity.sum() * (25.0 / 20.0)
        assertEquals(expected, getGoldIncome(cities, capitalId, 5, 25.0, null, pipeline), 1e-9)
        // verify the sum is of ROUNDED ints (not a single float round at the end)
        assertEquals(perCity.sum().toDouble() * (25.0 / 20.0), getGoldIncome(cities, capitalId, 5, 25.0, null, pipeline), 1e-9)
    }

    @Test
    fun `getRiceIncome and getWallIncome scale by taxRate over 20`() {
        val cities = listOf(city())
        val rice = calcCityRiceIncome(cities[0], 0, false, 5, null, pipeline)
        assertEquals(rice * (20.0 / 20.0), getRiceIncome(cities, -1, 5, 20.0, null, pipeline), 1e-9)
        val wall = calcCityWallRiceIncome(cities[0], 0, false, 5, null, pipeline)
        assertEquals(wall * (40.0 / 20.0), getWallIncome(cities, -1, 5, 40.0, null, pipeline), 1e-9)
    }

    @Test
    fun `getGoldIncome with empty city list is zero`() {
        assertEquals(0.0, getGoldIncome(emptyList(), -1, 5, 20.0, null, pipeline))
    }

    @Test
    fun `getOutcome sums getBill per general then rounds sum times billRate over 100`() {
        // getBill(ded) = getBillByLevel(getDedLevel(ded)) ; getDedLevel(0)=0 → bill 400 ; getDedLevel(10000)?
        val deds = listOf(0.0, 0.0, 10000.0)
        val sumBill = deds.sumOf { getBill(it) }
        val billRate = 130.0
        assertEquals(phpRound(sumBill * billRate / 100.0), getOutcome(billRate, deds))
    }

    @Test
    fun `getBill delegates to getBillByLevel over getDedLevel`() {
        assertEquals(getBillByLevel(getDedLevel(0.0)), getBill(0.0))
        assertEquals(getBillByLevel(getDedLevel(40000.0)), getBill(40000.0))
        assertEquals(400, getBill(0.0)) // dedLevel 0 → 0*200+400
    }

    // === strategic double-round consumer (research Unit 12 / che_의병모집.php:70-74) ===

    @Test
    fun `strategic delay consumer rounds base then folds through onCalcStrategic`() {
        // consumer: nextTerm = phpRound(base) ; then onCalcStrategic('delay', nextTerm) rounds AGAIN.
        // a *0.80 strategic module (평만지장도-shaped) over a base 9.4 → round 9 → *0.8=7.2 → round 7.
        val strategicModule = object : GeneralActionModule {
            override fun onCalcStrategic(general: General, varType: String, value: Double): Double =
                if (varType == "delay") phpRound(value * 0.80).toDouble() else value
        }
        val p = GeneralActionPipeline(listOf(strategicModule))
        val g = General(
            id = 1, nationId = 1, cityId = 1,
            leadership = 1, strength = 1, intel = 1, injury = 0,
            experience = 0.0, dedication = 0.0, officerLevel = 1, gold = 0, rice = 0,
        )
        assertEquals(7, calcStrategicDelay(9.4, "강행정찰", g, p))
        // no strategic module → just the base round
        assertEquals(9, calcStrategicDelay(9.4, "강행정찰", g, pipeline))
    }
}
