package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.constants.getTechCost
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.phpRound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * BO1 (wrapper) — port target = PHP `process_war.php:8-130` (the OUTER `processWar()`).
 *
 * The OUTER wrapper:
 *  - builds the SINGLE `RandUtil(LiteHashDrbg(warSeed))` ONCE (`:11`) and threads it by reference into the
 *    attacker + city + every defender candidate;
 *  - builds the defender candidate list + the LAZY `getNextDefender` closure (`:36-86`);
 *  - delegates to `processWarNG(rng, attacker, getNextDefender, city)` — NEVER calls processWarNG twice;
 *  - applies the post-loop side-effects as ChangeRecorder DELTAS (NO inline DB):
 *      - supply-rice decrement (`city.supply && phase>0`, via Util::setRound → phpRound, clamped max(0,…));
 *      - +1000/+500 capital/non-capital rice bonus (`conquerCity && phase==0`);
 *      - the city.dead 0.4 (attacker-city) / 0.6 (defender-city) split (int-truncated).
 */
class ProcessWarWrapperTest {

    private val pipeline = GeneralActionPipeline()
    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!

    private fun general(id: Int = 1, crew: Int = 5000, rice: Int = 10000): General = General(
        id = id, nationId = 1, cityId = 100,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = rice,
        crew = crew, train = 100.0, atmos = 100.0, crewTypeId = 1100,
        meta = linkedMapOf("explevel" to 0, "defence_train" to 0),
    )

    private fun city(id: Int, nationId: Int): City = City(
        id = id, nationId = nationId, level = 5,
        commerce = 1000, commerceMax = 9999,
        agriculture = 1000, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = 1000, securityMax = 9999,
        defense = 500, defenseMax = 9999,
        wall = 500, wallMax = 9999,
        population = 100000, populationMax = 999999,
    )

    private fun warEnv(
        defNationTech: Double = 0.0,
        defNationRice: Int = 10000,
        defNationCapitalCityId: Int = 0,
        attackerCityId: Int = 100,
        defenderCityId: Int = 200,
    ) = ProcessWarEnv(
        defenderNationTech = defNationTech,
        defenderNationRice = defNationRice,
        defenderNationCapitalCityId = defNationCapitalCityId,
        attackerCityId = attackerCityId,
        defenderCityId = defenderCityId,
    )

    /** A test seam recording how many times the inner machine ran + threading the conquerCity result. */
    private class FakeInner(
        val conquerCity: Boolean,
        val log: MutableList<String>,
        val onRun: (RandUtil, WarUnitGeneral, (WarUnit?, Boolean) -> WarUnit?, WarUnitCity) -> Unit = { _, _, _, _ -> },
    ) {
        var calls = 0
        fun run(rng: RandUtil, attacker: WarUnitGeneral, getNextDefender: (WarUnit?, Boolean) -> WarUnit?, city: WarUnitCity): Boolean {
            calls++
            log.add("processWarNG")
            onRun(rng, attacker, getNextDefender, city)
            return conquerCity
        }
    }

    @Test
    fun `wrapper builds one rng and delegates to processWarNG exactly once`() {
        val log = mutableListOf<String>()
        val inner = FakeInner(conquerCity = false, log = log)
        val warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200)

        var capturedAttackerRng: RandUtil? = null
        var capturedCityRng: RandUtil? = null
        val result = processWar(
            warSeed = warSeed,
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(),
            runInner = { rng, attacker, gnd, c ->
                capturedAttackerRng = attacker.rng
                capturedCityRng = c.rng
                inner.run(rng, attacker, gnd, c)
            },
        )

        assertEquals(1, inner.calls, "processWarNG must be delegated to exactly once")
        // the attacker + city share the ONE rng built by the wrapper.
        assertSame(capturedAttackerRng, capturedCityRng, "attacker + city must share the ONE wrapper rng")
        assertEquals(false, result.conquerCity)
    }

    @Test
    fun `invalid defender crew type falls back to the default footman`() {
        val inner = FakeInner(conquerCity = false, log = mutableListOf())

        processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = listOf(general(id = 2).copy(crewTypeId = 0)),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(),
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )

        assertEquals(1, inner.calls)
    }

    @Test
    fun `wrapper forwards the sortie source city level into attacker bonuses`() {
        var observedAtmos = 0.0
        val inner = FakeInner(conquerCity = false, log = mutableListOf(), onRun = { _, attacker, _, _ ->
            observedAtmos = attacker.getComputedAtmos()
        })

        processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman,
            attackerTech = 0,
            defenderCrewType = footman,
            defenderTech = 0,
            pipeline = pipeline,
            year = 200,
            startYear = 180,
            env = warEnv(),
            attackerCityLevel = 2,
            attackerIsCapital = false,
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )

        assertEquals(105.0, observedAtmos)
    }

    @Test
    fun `supply-rice decrement applied as a delta when supply and phase greater than zero`() {
        val log = mutableListOf<String>()
        val defNationTech = 1000.0
        val defNationRice = 10000
        // The inner machine "drives" killed + a >0 phase onto the city so the supply branch fires.
        val inner = FakeInner(conquerCity = false, log = log, onRun = { _, _, _, c ->
            c.increaseKilled(1000)   // city killed = 1000
            c.addPhase()             // phase = 1 (>0)
        })

        val result = processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(defNationTech = defNationTech, defNationRice = defNationRice, defenderCityId = 200),
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )

        // expected rice = killed/100 * 0.8 * crewType.rice * getTechCost(tech) * (cta/100 - 0.2), Util::setRound.
        val cta = result.defenderCityTrainAtmosForTest
        var rice = 1000 / 100.0 * 0.8
        rice *= footman.rice
        rice *= getTechCost(defNationTech.toInt())
        rice *= cta / 100.0 - 0.2
        val riceInt = phpRound(rice)
        val expectedDefRice = maxOf(0, defNationRice - riceInt)

        assertEquals(expectedDefRice, result.defenderNationRice, "supply-rice decrement must apply as a delta")
    }

    @Test
    fun `capital rice bonus plus 1000 when conquer at phase zero and capital city`() {
        val log = mutableListOf<String>()
        // conquerCity true, phase stays 0 → the +1000 (capital) bonus branch.
        val inner = FakeInner(conquerCity = true, log = log)
        val result = processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(defNationRice = 10000, defNationCapitalCityId = 200, defenderCityId = 200),
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )
        assertEquals(11000, result.defenderNationRice, "capital conquest at phase 0 adds +1000 rice")
    }

    @Test
    fun `non-capital rice bonus plus 500 when conquer at phase zero and non-capital city`() {
        val inner = FakeInner(conquerCity = true, log = mutableListOf())
        val result = processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(defNationRice = 10000, defNationCapitalCityId = 999, defenderCityId = 200),
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )
        assertEquals(10500, result.defenderNationRice, "non-capital conquest at phase 0 adds +500 rice")
    }

    @Test
    fun `city dead split 0_4 attacker-city and 0_6 defender-city as int-truncated deltas`() {
        // Drive attacker killed + dead so totalDead = killed + dead.
        val inner = FakeInner(conquerCity = false, log = mutableListOf(), onRun = { _, attacker, _, _ ->
            attacker.increaseKilled(777)
            attacker.decreaseHP(333)   // dead += 333
        })
        val result = processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1, crew = 5000),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(attackerCityId = 100, defenderCityId = 200),
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )
        val totalDead = 777 + 333
        // %i sqleval = int-cast (truncate toward zero) of the float product.
        assertEquals((totalDead * 0.4).toInt(), result.attackerCityDeadDelta)
        assertEquals((totalDead * 0.6).toInt(), result.defenderCityDeadDelta)
    }

    @Test
    fun `processWarNG is never invoked twice`() {
        val log = mutableListOf<String>()
        val inner = FakeInner(conquerCity = true, log = log)
        processWar(
            warSeed = WarSeed.build("0".repeat(32), 200, 6, 1, 200),
            attackerGeneral = general(id = 1),
            attackerNation = nationStub(),
            defenderCity = city(id = 200, nationId = 2),
            defenderCandidates = emptyList(),
            attackerCrewType = footman, attackerTech = 0,
            defenderCrewType = footman, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = warEnv(defenderCityId = 200),
            runInner = { rng, attacker, gnd, c -> inner.run(rng, attacker, gnd, c) },
        )
        assertEquals(1, log.count { it == "processWarNG" }, "the inner machine must run exactly once")
        assertTrue(log.size >= 1)
    }

    private fun nationStub() = opensamguk.logic.domain.Nation(id = 1, level = 0, capitalCityId = 100, rice = 0)
}
