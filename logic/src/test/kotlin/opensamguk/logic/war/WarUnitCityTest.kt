package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port target = PHP `WarUnitCity.php:12-195`. Pins the city formulas + the strict decreaseHP order + the
 * finishBattle round + the DEAD-CODE wealth block + NO own RNG draw. A recording RNG asserts the city
 * pulls ZERO draws on construction or in any getter (draws flow only through the inherited WarUnit sites,
 * exercised at the phase level by F3/B1).
 */
class WarUnitCityTest {

    private class RecordingRng(seed: ByteArray, val log: MutableList<String>) :
        RandUtil(LiteHashDrbg(seed)) {
        override fun nextRange(min: Double, max: Double): Double { log.add("nextRange"); return super.nextRange(min, max) }
        override fun nextRangeInt(a: Int, b: Int): Int { log.add("nextRangeInt"); return super.nextRangeInt(a, b) }
        override fun nextBool(prob: Double): Boolean { log.add("nextBool"); return super.nextBool(prob) }
    }

    private val seed = ByteArray(32) { it.toByte() }

    private fun city(
        id: Int = 10,
        level: Int = 5,
        def: Int = 300,
        wall: Int = 1000,
        agri: Int = 500,
        comm: Int = 400,
        secu: Int = 600,
    ): City = City(
        id = id, nationId = 1, level = level,
        commerce = comm, commerceMax = 9999,
        agriculture = agri, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = secu, securityMax = 9999,
        defense = def, defenseMax = 9999,
        wall = wall, wallMax = 9999,
        population = 100000, populationMax = 999999,
    )

    private fun unit(c: City = city(), year: Int = 201, startYear: Int = 181, rng: RandUtil = RandUtil(LiteHashDrbg(seed))): WarUnitCity =
        WarUnitCity(rng, WarUnitCityState(c), year, startYear)

    @Test
    fun `HP equals def times 10`() {
        assertEquals(3000, unit(city(def = 300)).getHP())
    }

    @Test
    fun `attack equals defence equals (def plus wall times 9) over 500 plus 200 float`() {
        val u = unit(city(def = 300, wall = 1000))
        val expected = (300 + 1000 * 9) / 500.0 + 200  // (300+9000)/500 + 200 = 18.6 + 200 = 218.6
        assertEquals(expected, u.getComputedAttack(), 1e-9)
        assertEquals(expected, u.getComputedDefence(), 1e-9)
        assertEquals(218.6, u.getComputedAttack(), 1e-9)
    }

    @Test
    fun `cityTrainAtmos is year minus startYear plus 59 clamped 60 to 110`() {
        // 181: 181-181+59 = 59 → clamp → 60
        assertEquals(60, unit(year = 181, startYear = 181).getCityTrainAtmos())
        // 201: 201-181+59 = 79 → 79
        assertEquals(79, unit(year = 201, startYear = 181).getCityTrainAtmos())
        // 231: 231-181+59 = 109 → 109
        assertEquals(109, unit(year = 231, startYear = 181).getCityTrainAtmos())
        // 240: 240-181+59 = 118 → clamp → 110
        assertEquals(110, unit(year = 240, startYear = 181).getCityTrainAtmos())
    }

    @Test
    fun `trainBonus is plus 5 for level 1 and 3, never plus 10, none for level 5`() {
        // level 1 → train = cta + 5
        val l1 = unit(city(level = 1), year = 201, startYear = 181)  // cta=79
        assertEquals(84.0, l1.getComputedTrain(), 1e-9)
        val l3 = unit(city(level = 3), year = 201, startYear = 181)
        assertEquals(84.0, l3.getComputedTrain(), 1e-9)
        // level 5 → no bonus
        val l5 = unit(city(level = 5), year = 201, startYear = 181)
        assertEquals(79.0, l5.getComputedTrain(), 1e-9)
        // atmos has no city bonus regardless of level
        assertEquals(79.0, l1.getComputedAtmos(), 1e-9)
    }

    @Test
    fun `getDex is cta minus 60 times 7200`() {
        val u = unit(year = 201, startYear = 181)  // cta=79
        val castle = GameUnitConst.byId(GameUnitConst.CREWTYPE_CASTLE)!!
        assertEquals((79 - 60) * 7200.0, u.getDex(castle), 1e-9)
    }

    @Test
    fun `decreaseHP strict order - dead accrues, hp drops, wall minus damage over 20 floored 0`() {
        val u = unit(city(def = 300, wall = 1000))  // hp=3000, wall=1000.0
        val ret = u.decreaseHP(400)
        assertEquals(2600, ret)
        assertEquals(2600, u.getHP())
        assertEquals(400, u.getDead())
        assertEquals(400, u.getDeadCurrentBattle())
        // wall -= 400/20 = 20 → 980
        assertEquals(980.0, u.state.wall, 1e-9)
    }

    @Test
    fun `decreaseHP clamps damage to hp and floors wall at 0`() {
        val u = unit(city(def = 10, wall = 5))  // hp=100, wall=5.0
        u.decreaseHP(100000)                    // clamp to hp=100
        assertEquals(0, u.getHP())
        assertEquals(100, u.getDead())          // only the clamped 100 counts
        assertEquals(0.0, u.state.wall, 1e-9)   // 5 - 100000/20 floored to 0
    }

    @Test
    fun `finishBattle sets def to round hp over 10 and wall to round wall`() {
        val u = unit(city(def = 300, wall = 1005))
        u.decreaseHP(450)   // hp 3000→2550; wall 1005 - 450/20=22.5 → 982.5
        u.finishBattle()
        assertEquals(255, u.state.defense)          // round(2550/10) = 255
        assertEquals(983, u.state.snapshot().wall)  // round(982.5) = 983 (half-away)
    }

    @Test
    fun `finishBattle wealth block is unreachable - agri comm secu unchanged`() {
        val u = unit(city(def = 300, wall = 1000, agri = 500, comm = 400, secu = 600))
        u.increaseKilled(10000)  // would trigger killed/20 wealth decrement IF reachable
        u.decreaseHP(100)
        u.setSiege()             // onSiege=true so the guard's !onSiege is false
        u.finishBattle()
        // isFinished was set true before the guard → wealth block dead → agri/comm/secu unchanged
        val snap = u.state.snapshot()
        assertEquals(500, snap.agriculture)
        assertEquals(400, snap.commerce)
        assertEquals(600, snap.security)
    }

    @Test
    fun `applyDB rollback suppresses city battle logs`() {
        assertTrue(unit().suppressLog())
    }

    @Test
    fun `city makes ZERO own RNG draws on construction and in getters`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val u = unit(rng = rng)
        u.getHP(); u.getComputedAttack(); u.getComputedDefence()
        u.getComputedTrain(); u.getComputedAtmos()
        u.getDex(GameUnitConst.byId(GameUnitConst.CREWTYPE_CASTLE)!!)
        u.decreaseHP(100); u.increaseKilled(100); u.finishBattle()
        assertTrue(log.isEmpty(), "WarUnitCity must make NO own draws, log=$log")
    }

    @Test
    fun `increaseKilled accrues killed and killedCurr only`() {
        val u = unit()
        u.increaseKilled(250)
        assertEquals(250, u.getKilled())
        assertEquals(250, u.getKilledCurrentBattle())
    }

    @Test
    fun `setSiege resets phase counters and unsets finished`() {
        val u = unit()
        u.addPhase(3); u.addBonusPhase(2)
        u.setSiege()
        assertTrue(u.isSiege())
        assertEquals(0, u.getPhase())
        assertEquals(0, u.getRealPhase())
    }
}
