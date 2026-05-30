package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.constants.getDexLog
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Port target = PHP `WarUnit.php:271-447`. Pins the draw-bearing math + the `<100`-floor CONDITIONAL draw
 * (the #1 desync trap) via a recording RNG. F2 base is exercised through a minimal concrete [TestUnit]
 * that hand-feeds the computed inputs (getComputedAttack/Defence/Train/Atmos/Dex) so the warPower formula
 * can be reproduced bit-for-bit (OQ #6).
 */
class WarUnitTest {

    /** Records the SEMANTIC draw sequence (method + args) off the shared stream. */
    private class RecordingRng(seed: ByteArray, private val log: MutableList<String>) :
        RandUtil(LiteHashDrbg(seed)) {
        override fun nextRange(min: Double, max: Double): Double {
            log.add("nextRange($min,$max)"); return super.nextRange(min, max)
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            log.add("nextRangeInt($minInclusive,$maxInclusive)"); return super.nextRangeInt(minInclusive, maxInclusive)
        }
        override fun nextBool(prob: Double): Boolean {
            log.add("nextBool($prob)"); return super.nextBool(prob)
        }
    }

    /** Minimal concrete WarUnit feeding constant computed inputs — isolates the base math. */
    private class TestUnit(
        rng: RandUtil,
        attacker: Boolean,
        private val crew: GameUnitDetail,
        private val att: Double,
        private val def: Double,
        private val train: Double,
        private val atmos: Double,
        private val dex: Double,
        private val hp: Int = 1000,
    ) : WarUnit(rng) {
        private val token = if (attacker) "A" else "D"
        init { isAttackerFlag = attacker }
        override fun unitIdToken(): String = token
        override fun getCrewType(): GameUnitDetail = crew
        override fun getComputedAttack(): Double = att
        override fun getComputedDefence(): Double = def
        override fun getComputedTrain(): Double = train
        override fun getComputedAtmos(): Double = atmos
        override fun getDex(crewType: GameUnitDetail): Double = dex
        override fun getHP(): Int = hp
        override fun getName(): String = token
        override fun decreaseHP(damage: Int): Int { deadField += damage; deadCurrField += damage; return hp }
        override fun increaseKilled(damage: Int): Int { killedField += damage; killedCurrField += damage; return killedField }
        // F1-contract leftovers unused by the base math test
        override fun getComputedCriticalRatio(): Double = 0.0
        override fun getComputedAvoidRatio(): Double = 0.0
        override fun pushBattleDetailLog(message: String) {}
        override fun pushPlainActionLog(message: String) {}
        override fun getItemName(): String = ""
        override fun getItemRawName(): String = ""
        override fun deleteItem() {}
        override fun isGeneral(): Boolean = true
        override fun isCity(): Boolean = false
        override fun getMagicTrialProb(oppose: opensamguk.logic.war.trigger.WarUnit): Double = 0.0
        override fun getMagicSuccessProb(oppose: opensamguk.logic.war.trigger.WarUnit): Double = 0.7
        override fun foldMagicSuccessDamage(oppose: opensamguk.logic.war.trigger.WarUnit, magic: String, raw: Double): Double = raw
        override fun foldMagicFailDamage(oppose: opensamguk.logic.war.trigger.WarUnit, magic: String, raw: Double): Double = raw
    }

    private val seed = ByteArray(32) { it.toByte() }
    // 보병 1100 footman: speed/avoid/coef tables from GameUnitConst.
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!

    @Test
    fun `warPower above 100 takes NO floor draw`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        // att huge so armperphase(500)+att-def >= 100 → no floor draw
        val a = TestUnit(rng, true, footman, att = 300.0, def = 50.0, train = 100.0, atmos = 100.0, dex = 0.0)
        val d = TestUnit(rng, false, footman, att = 0.0, def = 50.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.setOppose(d); d.setOppose(a)
        a.computeWarPower()
        assertTrue(log.none { it.startsWith("nextRangeInt") }, "warPower>=100 must NOT draw the floor, log=$log")
    }

    @Test
    fun `warPower below 100 takes exactly one floor draw`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        // 500 + 10 - 450 = 60 < 100 → floor: max(0,60)=60, (60+100)/2=80, nextRangeInt(80,100)
        val a = TestUnit(rng, true, footman, att = 10.0, def = 450.0, train = 100.0, atmos = 100.0, dex = 0.0)
        val d = TestUnit(rng, false, footman, att = 0.0, def = 450.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.setOppose(d); d.setOppose(a)
        a.computeWarPower()
        assertEquals(listOf("nextRangeInt(80,100)"), log.filter { it.startsWith("nextRangeInt") })
    }

    @Test
    fun `computeWarPower reproduces the hand-computed formula bit-for-bit`() {
        // No floor (warPower>=100): 500 + 200 - 100 = 600.
        // *= atmos(90) /= train(110) *= getDexLog(dexAtt,dexDef) *= attackCoef(self vs oppose)
        val rng = RandUtil(LiteHashDrbg(seed))
        val dexA = 50000.0; val dexD = 12000.0
        val a = TestUnit(rng, true, footman, att = 200.0, def = 100.0, train = 110.0, atmos = 90.0, dex = dexA)
        val d = TestUnit(rng, false, footman, att = 0.0, def = 100.0, train = 110.0, atmos = 90.0, dex = dexD)
        a.setOppose(d); d.setOppose(a)
        val (wp, opMul) = a.computeWarPower()

        var expected = 500.0 + 200.0 - 100.0   // 600
        expected *= 90.0                         // atmos
        expected /= 110.0                        // oppose train
        expected *= getDexLog(dexA.toInt(), dexD.toInt())
        expected *= footman.getAttackCoef(footman)
        assertEquals(expected, wp, 1e-9)
        assertEquals(footman.getDefenceCoef(footman), opMul, 1e-9)
        // oppose's multiply was set to opposeWarPowerMultiply
        assertEquals(opMul, d.getWarPowerMultiply(), 1e-9)
    }

    @Test
    fun `getWarPower is warPower times multiply`() {
        val rng = RandUtil(LiteHashDrbg(seed))
        val a = TestUnit(rng, true, footman, att = 200.0, def = 100.0, train = 100.0, atmos = 100.0, dex = 0.0)
        val d = TestUnit(rng, false, footman, att = 0.0, def = 100.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.setOppose(d); d.setOppose(a)
        a.computeWarPower()
        a.setWarPowerMultiply(2.0)
        assertEquals(a.getRawWarPower() * 2.0, a.getWarPower(), 1e-9)
        a.multiplyWarPowerMultiply(0.5)
        assertEquals(a.getRawWarPower() * 1.0, a.getWarPower(), 1e-9)
    }

    @Test
    fun `calcDamage is round of warPower times one nextRange draw`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val a = TestUnit(rng, true, footman, att = 200.0, def = 100.0, train = 100.0, atmos = 100.0, dex = 0.0)
        val d = TestUnit(rng, false, footman, att = 0.0, def = 100.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.setOppose(d); d.setOppose(a)
        a.computeWarPower()
        log.clear()
        // mirror the draw on a parallel stream to predict the rounded value
        val mirror = RandUtil(LiteHashDrbg(seed))
        // burn the same draws the computeWarPower above consumed: none (warPower>=100, no floor) — but
        // computeWarPower drew nothing here, so the stream is at the same position as a fresh seed only if
        // calcDamage is the FIRST draw. Recompute predictably:
        val expected = phpRound(a.getWarPower() * mirror.nextRange(0.9, 1.1))
        val dmg = a.calcDamage()
        assertEquals(listOf("nextRange(0.9,1.1)"), log)
        assertEquals(expected, dmg)
    }

    @Test
    fun `criticalDamage uses one nextRange over the default range`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val a = TestUnit(rng, true, footman, att = 0.0, def = 0.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.criticalDamage()
        assertEquals(listOf("nextRange(1.3,2.0)"), log)
    }

    @Test
    fun `beginPhase clears activated skills and recomputes`() {
        val rng = RandUtil(LiteHashDrbg(seed))
        val a = TestUnit(rng, true, footman, att = 200.0, def = 100.0, train = 100.0, atmos = 100.0, dex = 0.0)
        val d = TestUnit(rng, false, footman, att = 0.0, def = 100.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.setOppose(d); d.setOppose(a)
        a.activateSkill("필살")
        assertTrue(a.hasActivatedSkill("필살"))
        a.beginPhase()
        // clearActivatedSkill moved it to the log; no longer "active"
        assertTrue(!a.hasActivatedSkill("필살"))
        assertEquals(1, a.getActivatedSkillLog()["필살"])
        assertTrue(a.getRawWarPower() > 0.0)
    }

    @Test
    fun `skill state activate deactivate and onLog accounting`() {
        val rng = RandUtil(LiteHashDrbg(seed))
        val a = TestUnit(rng, true, footman, att = 0.0, def = 0.0, train = 100.0, atmos = 100.0, dex = 0.0)
        a.activateSkill("부상무효")
        assertEquals(1, a.hasActivatedSkillOnLog("부상무효"))  // active=+1
        a.deactivateSkill("부상무효")
        assertEquals(0, a.hasActivatedSkillOnLog("부상무효"))
    }
}
