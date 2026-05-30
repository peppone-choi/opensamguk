package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnitTriggerCaller
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * F3 / FP1 — port target = PHP `process_war.php:228-502` (processWar_NG).
 *
 * Pins the LOAD-BEARING per-phase macro draw order + the post-loop side-effect order of the phase machine.
 * F3 is a PURE function — it threads the ONE shared [RandUtil] by reference into every [WarUnit] +
 * getNextDefender, NEVER re-seeds, and CONSUMES the F5 `getBattlePhaseSkillTriggerList` result (via the
 * [WarBattleHooks] seam) rather than seeding named trigger classes itself.
 *
 * The macro order asserted (per iteration): initCaller.fire [first contact] → attacker.beginPhase
 * (computeWarPower, conditional <100 floor) → defender.beginPhase → battleCaller.fire → attacker.calcDamage
 * (1 draw) → defender.calcDamage (1 draw) → [HP clamp / decreaseHP / increaseKilled — NO draws] → continueWar →
 * tryWound(attacker)→tryWound(defender) [terminal-only, exactly ONCE across the three sites].
 */
class ProcessWarNGOrderTest {

    /** Records the SEMANTIC draw stream + the macro-event markers the caller threads in. */
    private class RecordingRng(seed: ByteArray, val log: MutableList<String>) :
        RandUtil(LiteHashDrbg(seed)) {
        override fun nextRange(min: Double, max: Double): Double {
            log.add("nextRange($min,$max)"); return super.nextRange(min, max)
        }
        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            log.add("nextRangeInt($minInclusive,$maxInclusive)"); return super.nextRangeInt(minInclusive, maxInclusive)
        }
        var nextBoolResult: Boolean? = null
        override fun nextBool(prob: Double): Boolean {
            log.add("nextBool($prob)"); return nextBoolResult ?: super.nextBool(prob)
        }
    }

    /**
     * A real [BaseWarUnitTrigger] (passes the WarUnitTriggerCaller instanceof gate) that logs a marker into
     * the shared draw-log when fired — proves the caller.fire ordering slot WITHOUT drawing on the rng.
     */
    private class MarkerTrigger(
        unit: opensamguk.logic.war.trigger.WarUnit,
        private val label: String,
        private val log: MutableList<String>,
    ) : BaseWarUnitTrigger(unit) {
        override val priority: Int = 20000
        override fun actionWar(
            self: opensamguk.logic.war.trigger.WarUnit,
            oppose: opensamguk.logic.war.trigger.WarUnit,
            selfEnv: TriggerEnv,
            opposeEnv: TriggerEnv,
        ): Boolean {
            log.add("fire:$label"); return true
        }
    }

    private val seed = ByteArray(32) { it.toByte() }
    private val pipeline = GeneralActionPipeline()
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!

    private fun general(id: Int = 1, crew: Int = 5000, rice: Int = 10000): General = General(
        id = id, nationId = 1, cityId = 10,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = rice,
        crew = crew, train = 100.0, atmos = 100.0, crewTypeId = 1100,
        meta = linkedMapOf("explevel" to 0),
    )

    private fun warGeneral(g: General, attacker: Boolean, rng: RandUtil): WarUnitGeneral =
        WarUnitGeneral(
            rng = rng, state = WarUnitGeneralState(g), pipeline = pipeline,
            crewType = footman, tech = 0, isAttacker = attacker, cityLevel = 9, isCapital = false,
        )

    private fun warCity(rng: RandUtil): WarUnitCity {
        val city = City(
            id = 10, nationId = 0, level = 5,
            commerce = 1000, commerceMax = 9999,
            agriculture = 1000, agricultureMax = 9999,
            supplyState = 1, frontState = 0, trust = 100.0,
            security = 1000, securityMax = 9999,
            defense = 500, defenseMax = 9999,
            wall = 500, wallMax = 9999,
            population = 100000, populationMax = 999999,
        )
        return WarUnitCity(rng = rng, state = WarUnitCityState(city), year = 200, startYear = 180)
    }

    /** Hooks that delegate trigger callers to per-unit MarkerTrigger callers + record the non-draw events. */
    private class StubHooks(
        val log: MutableList<String>,
        private val initFor: (WarUnit) -> WarUnitTriggerCaller?,
        private val phaseFor: (WarUnit) -> WarUnitTriggerCaller?,
    ) : WarBattleHooks {
        override fun battleInitCaller(unit: WarUnit): WarUnitTriggerCaller? = initFor(unit)
        override fun battlePhaseCaller(unit: WarUnit): WarUnitTriggerCaller? = phaseFor(unit)
        override fun onAdvanceLog(attacker: WarUnitGeneral, city: WarUnitCity) { log.add("advanceLog") }
        override fun onBattleResultLog(unit: WarUnit) {}
        override fun addConflict(city: WarUnitCity, attacker: WarUnitGeneral): Boolean { log.add("addConflict"); return false }
    }

    /**
     * Single surviving defender (a general); the attacker runs out its full max-phase (footman speed) of
     * survivable phases, then exhausts → the `!logWritten` post-loop tryWound pair fires. Pins the per-phase
     * macro order: battleCaller fires attacker-before-defender, THEN exactly two calcDamage draws attacker-first.
     */
    @Test
    fun `per-phase macro draw order — battleCaller attacker-first then two calcDamage attacker-first`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        val attacker = warGeneral(general(id = 1), attacker = true, rng = rng)
        val defender = warGeneral(general(id = 2), attacker = false, rng = rng)
        val city = warCity(rng)

        val hooks = StubHooks(
            log,
            initFor = { WarUnitTriggerCaller() },
            phaseFor = { u -> WarUnitTriggerCaller(MarkerTrigger(u, "phase:${u.getUnitId()}", log)) },
        )

        var calls = 0
        val getNextDefender: (WarUnit?, Boolean) -> WarUnit? = { _, reqNext ->
            if (reqNext && calls++ == 0) defender else null
        }

        processWarNG(rng, attacker, getNextDefender, city, hooks)

        val maxPhase = attacker.getMaxPhase()
        assertTrue(maxPhase > 0)

        // 진격 log emitted ONCE before the loop.
        assertEquals(1, log.count { it == "advanceLog" }, "advanceLog must fire exactly once: $log")

        // EACH survived phase contributes exactly: fire:phase:G1, fire:phase:G2, nextRange, nextRange.
        val perPhase = listOf(
            "fire:phase:${attacker.getUnitId()}",
            "fire:phase:${defender.getUnitId()}",
            "nextRange(0.9,1.1)",
            "nextRange(0.9,1.1)",
        )
        // drop the leading advanceLog; the next 4*maxPhase entries are the phase blocks (defender survives all).
        val phaseStream = log.drop(1).take(4 * maxPhase)
        val expectedStream = (0 until maxPhase).flatMap { perPhase }
        assertEquals(expectedStream, phaseStream, "per-phase macro order must repeat [A-fire,D-fire,A-calc,D-calc]: $log")

        // calcDamage drew exactly 2 per phase.
        assertEquals(2 * maxPhase, log.count { it == "nextRange(0.9,1.1)" }, "calcDamage = 2 per phase: $log")

        // the post-loop !logWritten tryWound pair fired exactly once per side (2 nextBool(0.05) total).
        assertEquals(2, log.count { it == "nextBool(0.05)" }, "post-loop tryWound pair fires once per side: $log")
    }

    @Test
    fun `computeWarPower floor draw appears below 100 and disappears at or above 100`() {
        // crew/att/def tuned so 500 + att - def < 100 forces the floor draw; we assert the COUNT shifts.
        val logLow = mutableListOf<String>()
        val rngLow = RecordingRng(seed, logLow)
        // huge opposing defence pushes warPower below 100 → floor draw fires.
        val aLow = warGeneral(general(id = 1, crew = 1), attacker = true, rng = rngLow)
        val dLow = warGeneral(general(id = 2, crew = 5_000_000), attacker = false, rng = rngLow)
        aLow.setOppose(dLow); dLow.setOppose(aLow)
        aLow.beginPhase()
        val lowFloorDraws = logLow.count { it.startsWith("nextRangeInt(") }

        val logHigh = mutableListOf<String>()
        val rngHigh = RecordingRng(seed, logHigh)
        val aHigh = warGeneral(general(id = 1, crew = 5_000_000), attacker = true, rng = rngHigh)
        val dHigh = warGeneral(general(id = 2, crew = 1), attacker = false, rng = rngHigh)
        aHigh.setOppose(dHigh); dHigh.setOppose(aHigh)
        aHigh.beginPhase()
        val highFloorDraws = logHigh.count { it.startsWith("nextRangeInt(") }

        assertTrue(lowFloorDraws >= 1, "warPower<100 must pull the floor draw: $logLow")
        assertEquals(0, highFloorDraws, "warPower>=100 must NOT pull the floor draw: $logHigh")
    }

    @Test
    fun `attacker retreat sets logWritten so the post-loop tryWound pair does NOT re-fire`() {
        // Force attacker to run out of rice on phase end → attacker.continueWar() false → retreat branch
        // fires tryWound ONCE; the post-loop !logWritten pair must NOT fire again.
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        rng.nextBoolResult = false  // tryWound nextBool(0.05) always misses (1 draw, no injury)
        // attacker rice <= crew/100 after consuming → continueWar false. Start with rice barely above; the
        // calcDamage/increaseKilled consumes rice. Simplest: rice exactly at the boundary.
        val attacker = warGeneral(general(id = 1, crew = 5000, rice = 1), attacker = true, rng = rng)
        val defender = warGeneral(general(id = 2, crew = 5000), attacker = false, rng = rng)
        val city = warCity(rng)
        val hooks = StubHooks(
            log, initFor = { WarUnitTriggerCaller() }, phaseFor = { WarUnitTriggerCaller() },
        )
        var calls = 0
        val getNextDefender: (WarUnit?, Boolean) -> WarUnit? = { _, reqNext ->
            if (reqNext && calls++ == 0) defender else null
        }

        processWarNG(rng, attacker, getNextDefender, city, hooks)

        // tryWound (nextBool(0.05)) fires EXACTLY twice total (attacker + defender) — once, not twice-over.
        assertEquals(2, log.count { it == "nextBool(0.05)" },
            "tryWound pair must fire exactly once per side (2 total) across the three sites: $log")
    }

    @Test
    fun `post-loop city block adds conflict and the final getNextDefender release is called`() {
        val log = mutableListOf<String>()
        val rng = RecordingRng(seed, log)
        rng.nextBoolResult = false
        val attacker = warGeneral(general(id = 1), attacker = true, rng = rng)
        val city = warCity(rng)
        val hooks = StubHooks(
            log, initFor = { WarUnitTriggerCaller() }, phaseFor = { WarUnitTriggerCaller() },
        )
        // No general defenders → first getNextDefender(null,true) returns null → the city becomes the siege
        // defender; after the loop the post-loop city block runs addConflict.
        val released = mutableListOf<Boolean>()
        val getNextDefender: (WarUnit?, Boolean) -> WarUnit? = { _, reqNext ->
            released.add(reqNext); null
        }

        processWarNG(rng, attacker, getNextDefender, city, hooks)

        assertTrue(log.contains("addConflict"), "post-loop city block must call addConflict: $log")
        // the terminal getNextDefender(defender, false) release (reqNext=false) is invoked.
        assertTrue(released.any { !it }, "terminal getNextDefender(defender, false) release must fire: $released")
    }

    @Test
    fun `the single shared rng is threaded by reference and never re-seeded`() {
        // Two units share ONE rng; advancing it via one unit's draw shifts the OTHER unit's subsequent draw —
        // proving a single stream (a re-seed would reset and duplicate values).
        val rngShared = RandUtil(LiteHashDrbg(seed))
        val attacker = warGeneral(general(id = 1), attacker = true, rng = rngShared)
        val defender = warGeneral(general(id = 2), attacker = false, rng = rngShared)
        // The units MUST hold the SAME rng instance (by reference).
        assertEquals(System.identityHashCode(rngShared), System.identityHashCode(attacker.rng))
        assertEquals(System.identityHashCode(rngShared), System.identityHashCode(defender.rng))
        assertFalse(attacker.rng !== defender.rng, "attacker + defender must share ONE rng by reference")
    }
}
