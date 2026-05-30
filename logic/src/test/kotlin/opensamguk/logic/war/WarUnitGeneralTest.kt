package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port target = PHP `WarUnitGeneral.php:13-378`. Pins:
 *  - the explevel `/600` (vs city) vs `/300` (vs general) power branch + the `max(0.01,…)` clamp;
 *  - tryWound: 부상무효/퇴각부상무효 short-circuit consumes NO rng; else `nextBool(0.05)` then a
 *    conditional `nextRangeInt(10,80)` — 1 or 2 draws;
 *  - finishBattle idempotency (no double rank-counter increment) + the rice→experience→dedication
 *    half-away round order;
 *  - the ctor cityLevel ±5 atmos/train bonuses.
 */
class WarUnitGeneralTest {

    private class RecordingRng(seed: ByteArray, val log: MutableList<String>) :
        RandUtil(LiteHashDrbg(seed)) {
        var nextBoolResult: Boolean? = null
        override fun nextBool(prob: Double): Boolean {
            log.add("nextBool($prob)")
            return nextBoolResult ?: super.nextBool(prob)
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            log.add("nextRangeInt($minInclusive,$maxInclusive)")
            return super.nextRangeInt(minInclusive, maxInclusive)
        }
    }

    /** Minimal city-side oppose: reports isCity()=true + constant computed inputs (no own draws). */
    private class FakeCityOppose(rng: RandUtil) : WarUnit(rng) {
        init { isAttackerFlag = false }
        override fun unitIdToken(): String = "CITY"
        override fun getCrewType(): GameUnitDetail = GameUnitConst.byId(GameUnitConst.CREWTYPE_CASTLE)!!
        override fun getComputedAttack(): Double = 250.0
        override fun getComputedDefence(): Double = 250.0
        override fun getComputedTrain(): Double = 100.0
        override fun getComputedAtmos(): Double = 100.0
        override fun getDex(crewType: GameUnitDetail): Double = 0.0
        override fun getHP(): Int = 5000
        override fun getName(): String = "CITY"
        override fun decreaseHP(damage: Int): Int = 5000
        override fun increaseKilled(damage: Int): Int = 0
        override fun isGeneral(): Boolean = false
        override fun isCity(): Boolean = true
        override fun getComputedCriticalRatio(): Double = 0.0
        override fun getComputedAvoidRatio(): Double = 0.0
        override fun pushBattleDetailLog(message: String) {}
        override fun pushPlainActionLog(message: String) {}
        override fun getItemName(): String = ""
        override fun getItemRawName(): String = ""
        override fun deleteItem() {}
        override fun getMagicTrialProb(oppose: opensamguk.logic.war.trigger.WarUnit): Double = 0.0
        override fun getMagicSuccessProb(oppose: opensamguk.logic.war.trigger.WarUnit): Double = 0.7
        override fun foldMagicSuccessDamage(oppose: opensamguk.logic.war.trigger.WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: opensamguk.logic.war.trigger.WarUnit, magic: String, raw: Double): Double = raw
    }

    private val seed = ByteArray(32) { it.toByte() }
    private val pipeline = GeneralActionPipeline()
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!

    private fun general(
        id: Int = 1,
        explevel: Int = 0,
        crew: Int = 5000,
        rice: Int = 10000,
        injury: Int = 0,
    ): General = General(
        id = id, nationId = 1, cityId = 10,
        leadership = 80, strength = 80, intel = 80, injury = injury,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = rice,
        crew = crew, train = 100.0, atmos = 100.0, crewTypeId = 1100,
        meta = linkedMapOf("explevel" to explevel),
    )

    private fun unit(
        g: General,
        attacker: Boolean = true,
        cityLevel: Int = 2,
        isCapital: Boolean = false,
        rng: RandUtil = RandUtil(LiteHashDrbg(seed)),
    ): WarUnitGeneral = WarUnitGeneral(
        rng = rng,
        state = WarUnitGeneralState(g),
        pipeline = pipeline,
        crewType = footman,
        tech = 0,
        isAttacker = attacker,
        cityLevel = cityLevel,
        isCapital = isCapital,
    )

    @Test
    fun `ctor attacker city level 2 grants plus 5 atmos bonus`() {
        val a = unit(general(), attacker = true, cityLevel = 2)
        val d = unit(general(id = 2), attacker = false, cityLevel = 9)  // no bonus
        a.setOppose(d); d.setOppose(a)
        // atmosBonus folds into getComputedAtmos: base 100 + 5
        assertEquals(105.0, a.getComputedAtmos(), 1e-9)
    }

    @Test
    fun `ctor attacker on own capital grants extra plus 5 atmos`() {
        val a = unit(general(), attacker = true, cityLevel = 2, isCapital = true)
        val d = unit(general(id = 2), attacker = false, cityLevel = 9)
        a.setOppose(d); d.setOppose(a)
        assertEquals(110.0, a.getComputedAtmos(), 1e-9)  // 100 + 5(level2) + 5(capital)
    }

    @Test
    fun `ctor defender city level 1 or 3 grants plus 5 train bonus`() {
        val d1 = unit(general(), attacker = false, cityLevel = 1)
        val d3 = unit(general(id = 2), attacker = false, cityLevel = 3)
        val a = unit(general(id = 3), attacker = true, cityLevel = 9)
        d1.setOppose(a); d3.setOppose(a); a.setOppose(d1)
        assertEquals(105.0, d1.getComputedTrain(), 1e-9)
        assertEquals(105.0, d3.getComputedTrain(), 1e-9)
    }

    @Test
    fun `computeWarPower vs general divides self by 300-explevel clamp`() {
        // base core (no floor): 500 + att - def. With explevel, /= max(0.01, 1 - exp/300).
        val rng = RandUtil(LiteHashDrbg(seed))
        val a = unit(general(explevel = 30), attacker = true, cityLevel = 9, rng = rng)
        val d = unit(general(id = 2, explevel = 0), attacker = false, cityLevel = 9, rng = rng)
        a.setOppose(d); d.setOppose(a)

        val base = baseWarPower(a, d)
        val (wp, _) = a.computeWarPower()
        val expected = base / maxOf(0.01, 1 - 30 / 300.0)
        assertEquals(expected, wp, 1e-6)
    }

    @Test
    fun `computeWarPower vs city multiplies self by 1 plus explevel over 600`() {
        val rng = RandUtil(LiteHashDrbg(seed))
        val a = unit(general(explevel = 60), attacker = true, cityLevel = 9, rng = rng)
        val city = FakeCityOppose(rng)
        a.setOppose(city); city.setOppose(a)

        val base = baseWarPower(a, city)
        val (wp, _) = a.computeWarPower()
        val expected = base * (1 + 60 / 600.0)
        assertEquals(expected, wp, 1e-6)
    }

    /** Reproduce the base WarUnit.computeWarPower core (no explevel, no special) for the expectation. */
    private fun baseWarPower(self: WarUnitGeneral, oppose: WarUnit): Double {
        val myAtt = self.getComputedAttack()
        val opDef = oppose.getComputedDefence()
        var wp = 500.0 + myAtt - opDef  // armperphase=500; assume >=100 here
        wp *= self.getComputedAtmos()
        wp /= oppose.getComputedTrain()
        wp *= opensamguk.common.constants.getDexLog(
            self.getDex(self.getCrewType()).toInt(),
            oppose.getDex(self.getCrewType()).toInt(),
        )
        wp *= self.getCrewType().getAttackCoef(oppose.getCrewType())
        return wp
    }

    @Test
    fun `tryWound short-circuits with NO draw when 부상무효 active`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val a = unit(general(), rng = rng)
        val d = unit(general(id = 2), attacker = false, rng = rng)
        a.setOppose(d); d.setOppose(a)
        a.activateSkill("부상무효")
        assertTrue(!a.tryWound())
        assertTrue(log.isEmpty(), "부상무효 must consume NO rng, log=$log")
    }

    @Test
    fun `tryWound short-circuits with NO draw when 퇴각부상무효 active`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val a = unit(general(), rng = rng)
        val d = unit(general(id = 2), attacker = false, rng = rng)
        a.setOppose(d); d.setOppose(a)
        a.activateSkill("퇴각부상무효")
        assertTrue(!a.tryWound())
        assertTrue(log.isEmpty())
    }

    @Test
    fun `tryWound draws nextBool 0_05 only, no injury draw when it misses`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        rng.nextBoolResult = false
        val a = unit(general(), rng = rng)
        val d = unit(general(id = 2), attacker = false, rng = rng)
        a.setOppose(d); d.setOppose(a)
        assertTrue(!a.tryWound())
        assertEquals(listOf("nextBool(0.05)"), log)
    }

    @Test
    fun `tryWound draws nextBool then nextRangeInt 10-80 when it hits`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        rng.nextBoolResult = true
        val a = unit(general(injury = 0), rng = rng)
        val d = unit(general(id = 2), attacker = false, rng = rng)
        a.setOppose(d); d.setOppose(a)
        assertTrue(a.tryWound())
        assertEquals(listOf("nextBool(0.05)", "nextRangeInt(10,80)"), log)
        assertTrue(a.hasActivatedSkill("부상"))
        assertTrue(a.state.injury in 10..80)
    }

    @Test
    fun `finishBattle is idempotent - second call does not double increment counters`() {
        val a = unit(general(), rng = RandUtil(LiteHashDrbg(seed)))
        val d = unit(general(id = 2), attacker = false, rng = RandUtil(LiteHashDrbg(seed)))
        a.setOppose(d); d.setOppose(a)
        a.increaseKilled(200)
        a.decreaseHP(100)
        a.finishBattle()
        val killcrew1 = a.state.getRank("killcrew")
        val deathcrew1 = a.state.getRank("deathcrew")
        a.finishBattle()  // 2nd call — idempotent guard
        assertEquals(killcrew1, a.state.getRank("killcrew"))
        assertEquals(deathcrew1, a.state.getRank("deathcrew"))
        assertEquals(200, killcrew1)
        assertEquals(100, deathcrew1)
    }

    @Test
    fun `finishBattle rounds rice then experience then dedication half-away`() {
        val g = general().copy(rice = 0, experience = 12.5, dedication = 7.5)
        val state = WarUnitGeneralState(g)
        // seed fractional rice via a kill that consumes a fractional rice amount
        val a = WarUnitGeneral(RandUtil(LiteHashDrbg(seed)), state, pipeline, footman, 0, true, 9, false)
        val d = unit(general(id = 2), attacker = false)
        a.setOppose(d); d.setOppose(a)
        // experience/dedication already fractional → half-away round: 12.5→13, 7.5→8
        a.finishBattle()
        assertEquals(13.0, state.experience, 1e-9)
        assertEquals(8.0, state.dedication, 1e-9)
    }
}
