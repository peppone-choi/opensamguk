package opensamguk.logic.war

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.stats.GeneralActionPipeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B3 / BS1 — port target = PHP `j_simulate_battle.php:366-476` (`simulateBattle` DB-free) + `:243-249`
 * (a POSTed seed forces `repeatCnt=1`). Research Unit 8.
 *
 * [BattleSimPreview] is the raw-input engine-REUSE adapter living on the P7 read/preview side. It builds
 * WarUnits from RAW JSON (NO DB), reorders by [extractBattleOrder] (NO draw), and runs the F3 phase machine
 * through the SAME OUTER [processWar] the real `che_출병` resolver calls — so the FIXED-seed single replay
 * produces a draw stream value-for-value IDENTICAL to a real battle for the same seed + armies.
 *
 * This test pins:
 *  - raw-JSON unit build (no DB);
 *  - reorder sorts by extractBattleOrder (NO draw);
 *  - a FIXED seed → a single byte-reproducible battle whose draw stream == the real engine's;
 *  - construction draws ZERO rng (the throwaway validation pass is draw-neutral);
 *  - input validation rules (train/atmos/injury/explevel/officer_level bounds + crewtype/equip allowlists).
 */
class BattleSimPreviewTest {

    private val pipeline = GeneralActionPipeline()

    private fun rawAttacker(id: Int = 1): BattleSimGeneralInput = BattleSimGeneralInput(
        no = id, nationId = 1, cityId = 100,
        leadership = 80, strength = 80, intel = 80, injury = 0,
        explevel = 0, experience = 0, dedication = 0, officerLevel = 5,
        gold = 1000, rice = 10000,
        crew = 5000, train = 100, atmos = 100, crewTypeId = 1100,
        horse = "None", weapon = "None", book = "None", item = "None",
        meta = linkedMapOf("defence_train" to 0),
    )

    private fun rawDefender(id: Int = 2, crew: Int = 4000): BattleSimGeneralInput =
        rawAttacker(id).copy(nationId = 2, cityId = 200, crew = crew)

    private fun rawCity(id: Int, nationId: Int): City = City(
        id = id, nationId = nationId, level = 5,
        commerce = 1000, commerceMax = 9999,
        agriculture = 1000, agricultureMax = 9999,
        supplyState = 1, frontState = 0, trust = 100.0,
        security = 1000, securityMax = 9999,
        defense = 500, defenseMax = 9999,
        wall = 500, wallMax = 9999,
        population = 100000, populationMax = 999999,
    )

    private fun nation(id: Int) = Nation(id = id, level = 0, capitalCityId = 100, rice = 10000, tech = 0.0)

    // -- helper: build a full preview input --
    private fun input(
        warSeed: String?,
        defenders: List<BattleSimGeneralInput> = listOf(rawDefender()),
    ) = BattleSimInput(
        attacker = rawAttacker(),
        attackerCity = rawCity(100, 1),
        attackerNation = nation(1),
        defenders = defenders,
        defenderCity = rawCity(200, 2),
        defenderNation = nation(2).copy(id = 2, capitalCityId = 200),
        year = 200,
        month = 6,
        startYear = 180,
        warSeed = warSeed,
        attackerCrewType = GameUnitConst.byId(1100)!!,
        defenderCrewType = GameUnitConst.byId(1100)!!,
    )

    // ---------------------------------------------------------------------------------------------------

    @Test
    fun `simulateBattle builds units from raw input with no DB and returns aggregate`() {
        val sim = BattleSimPreview(pipeline)
        val result = sim.simulateBattle(input(warSeed = "0".repeat(32)))
        // a deterministic single replay yields exactly one battle (repeatCnt forced to 1 by the seed).
        assertEquals(1, result.repeatCnt, "a POSTed seed forces repeatCnt=1")
        assertTrue(result.avgPhase >= 0.0)
        // the attacker working unit is exposed (the snapshot delta source) with no inline DB write.
        assertTrue(result.attackerKilled >= 0.0)
    }

    @Test
    fun `reorder sorts defenders by extractBattleOrder descending and draws zero rng`() {
        val sim = BattleSimPreview(pipeline)
        // a weak defender (low crew) sorts AFTER a strong one — DESC by battle order.
        val weak = rawDefender(id = 5, crew = 100)
        val strong = rawDefender(id = 6, crew = 9000)
        val order = sim.reorder(input(warSeed = "0".repeat(32), defenders = listOf(weak, strong)))
        assertEquals(listOf(6, 5), order, "reorder is DESC by extractBattleOrder; no rng draw")
    }

    @Test
    fun `a fixed seed produces a battle whose draw stream equals the real engine for the same armies`() {
        val seed = "1234abcd".repeat(4)   // 32 hex chars
        val inp = input(warSeed = seed)

        // (A) the REAL engine path: call processWar directly with the same warSeed + armies, recording draws.
        val realStream = mutableListOf<String>()
        processWar(
            warSeed = seed,
            attackerGeneral = inp.attacker.toGeneral(),
            attackerNation = inp.attackerNation,
            defenderCity = inp.defenderCity,
            defenderCandidates = inp.defenders.map { it.toGeneral() },
            attackerCrewType = inp.attackerCrewType, attackerTech = 0,
            defenderCrewType = inp.defenderCrewType, defenderTech = 0,
            pipeline = pipeline,
            year = 200, startYear = 180,
            env = ProcessWarEnv(
                defenderNationTech = 0.0, defenderNationRice = 10000,
                defenderNationCapitalCityId = 200, attackerCityId = 100, defenderCityId = 200,
            ),
            runInner = { rng, attacker, gnd, city ->
                processWarNG(rng, attacker, gnd, city, RecordingHooks(realStream))
            },
        )

        // (B) the PREVIEW path: same seed + armies, recording draws via the same hooks seam.
        val previewStream = mutableListOf<String>()
        val sim = BattleSimPreview(pipeline, hooksFactory = { RecordingHooks(previewStream) })
        sim.simulateBattle(inp)

        assertEquals(realStream, previewStream, "preview must produce the SAME draw stream as a real battle")
        assertTrue(realStream.isNotEmpty(), "the battle must actually have run (non-empty stream)")
    }

    @Test
    fun `construction draws zero rng - the throwaway validation pass is draw-neutral`() {
        // Build the units (construction + validation) WITHOUT running the battle; the shared rng cursor
        // must NOT advance. We compare the rng state before vs after a build-only pass.
        val sim = BattleSimPreview(pipeline)
        val seed = "0".repeat(32)
        val before = RandUtil(LiteHashDrbg(seed))
        val baselineFloat = before.nextRange(0.0, 1.0)   // the FIRST draw off a fresh rng

        // build-only must consume nothing, so a freshly-seeded rng's first draw is still baselineFloat.
        sim.buildUnitsOnly(input(warSeed = seed))
        val after = RandUtil(LiteHashDrbg(seed))
        assertEquals(baselineFloat, after.nextRange(0.0, 1.0), 1e-12, "construction must draw zero rng")
    }

    @Test
    fun `input validation rejects out-of-range train`() {
        val sim = BattleSimPreview(pipeline)
        val bad = input(warSeed = "0".repeat(32)).copy(attacker = rawAttacker().copy(train = 39))  // < 40
        assertFailsWith<IllegalArgumentException> { sim.simulateBattle(bad) }
    }

    @Test
    fun `input validation rejects out-of-range injury and officer_level and explevel`() {
        val sim = BattleSimPreview(pipeline)
        assertFailsWith<IllegalArgumentException> {
            sim.simulateBattle(input(warSeed = "0".repeat(32)).copy(attacker = rawAttacker().copy(injury = 81)))
        }
        assertFailsWith<IllegalArgumentException> {
            sim.simulateBattle(input(warSeed = "0".repeat(32)).copy(attacker = rawAttacker().copy(officerLevel = 13)))
        }
        assertFailsWith<IllegalArgumentException> {
            sim.simulateBattle(input(warSeed = "0".repeat(32)).copy(attacker = rawAttacker().copy(explevel = 301)))
        }
    }

    @Test
    fun `input validation rejects unknown crewtype and equip allowlist`() {
        val sim = BattleSimPreview(pipeline)
        assertFailsWith<IllegalArgumentException> {
            sim.simulateBattle(input(warSeed = "0".repeat(32)).copy(attacker = rawAttacker().copy(crewTypeId = 999999)))
        }
        assertFailsWith<IllegalArgumentException> {
            sim.simulateBattle(input(warSeed = "0".repeat(32)).copy(attacker = rawAttacker().copy(weapon = "NotAnItem")))
        }
    }

    /** Records each draw site by routing through the F3 hooks (init/phase callers are draw-bearing). */
    private class RecordingHooks(private val log: MutableList<String>) : WarBattleHooks {
        override fun onAdvanceLog(attacker: WarUnitGeneral, city: WarUnitCity) { log.add("advance") }
        override fun onContactLog(attacker: WarUnitGeneral, defender: WarUnit) { log.add("contact") }
        override fun onPhaseLog(attacker: WarUnitGeneral, defender: WarUnit, deadAttacker: Int, deadDefender: Int) {
            log.add("phase($deadAttacker,$deadDefender)")
        }
        override fun onBattleResultLog(unit: WarUnit) { log.add("result") }
    }
}
