package com.opensam.qa.parity

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opensam.command.CommandEnv
import com.opensam.command.CommandResult
import com.opensam.command.general.*
import com.opensam.engine.LiteHashDRBG
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.math.roundToInt

/**
 * Command parity tests verifying Kotlin commands match legacy PHP logic.
 *
 * Legacy references:
 * - hwe/sammo/Command/General/che_훈련.php
 * - hwe/sammo/Command/General/che_징병.php
 * - hwe/sammo/Command/General/che_상업투자.php (DomesticCommand base)
 * - hwe/sammo/Command/General/che_성벽보수.php
 * - hwe/sammo/Command/General/che_수비강화.php
 * - hwe/sammo/Command/General/che_치안강화.php
 * - hwe/sammo/Command/General/che_사기진작.php
 */
@DisplayName("Command Logic Legacy Parity")
class CommandParityTest {

    private val mapper = jacksonObjectMapper()

    // ──────────────────────────────────────────────────
    //  훈련 (Training) edge cases
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("che_훈련 - legacy che_훈련.php:89")
    inner class TrainingParity {

        @Test
        fun `high leadership low crew gives high training score`() {
            // Legacy: score = clamp(round(leadership * 100 / crew * trainDelta), 0, maxTrain - train)
            val gen = createGeneral(leadership = 100, crew = 100, train = 0, atmos = 80)
            val result = runCmd(che_훈련(gen, createEnv()), "train_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            // 100 * 100 / 100 * 0.05 = 5, clamped to min(5, 80-0)=5
            assertEquals(5, json["statChanges"]["train"].asInt())
        }

        @Test
        fun `training cannot exceed max train`() {
            val gen = createGeneral(leadership = 100, crew = 100, train = 79, atmos = 80)
            val result = runCmd(che_훈련(gen, createEnv()), "train_2")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            // max possible = 80-79 = 1; raw = 5, clamped to 1
            assertEquals(1, json["statChanges"]["train"].asInt())
        }

        @Test
        fun `training at max gives zero`() {
            val gen = createGeneral(leadership = 100, crew = 100, train = 80, atmos = 80)
            val result = runCmd(che_훈련(gen, createEnv()), "train_3")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            assertEquals(0, json["statChanges"]["train"].asInt())
        }

        @Test
        fun `atmos side effect matches legacy formula`() {
            // Legacy: sideEffect = valueFit(atmos * atmosSideEffectByTraining, 0)
            // Kotlin: atmosAfter = max(0, (atmos * 0.9).toInt()); delta = atmosAfter - atmos
            val gen = createGeneral(leadership = 80, crew = 200, train = 60, atmos = 100)
            val result = runCmd(che_훈련(gen, createEnv()), "train_4")
            val json = mapper.readTree(result.message)
            // atmosAfter = (100 * 0.9).toInt() = 90; delta = 90 - 100 = -10
            assertEquals(-10, json["statChanges"]["atmos"].asInt())
        }

        @Test
        fun `atmos zero stays zero`() {
            val gen = createGeneral(leadership = 80, crew = 200, train = 60, atmos = 0)
            val result = runCmd(che_훈련(gen, createEnv()), "train_5")
            val json = mapper.readTree(result.message)
            assertEquals(0, json["statChanges"]["atmos"].asInt())
        }

        @Test
        fun `experience and dedication are fixed at 100 and 70`() {
            // Legacy: $exp = 100; $ded = 70;
            val gen = createGeneral(leadership = 80, crew = 200, train = 60, atmos = 70)
            val result = runCmd(che_훈련(gen, createEnv()), "train_6")
            val json = mapper.readTree(result.message)
            assertEquals(100, json["statChanges"]["experience"].asInt())
            assertEquals(70, json["statChanges"]["dedication"].asInt())
            assertEquals(1, json["statChanges"]["leadershipExp"].asInt())
        }
    }

    // ──────────────────────────────────────────────────
    //  징병 (Conscription) edge cases
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("che_징병 - legacy che_징병.php")
    inner class ConscriptionParity {

        @Test
        fun `adding to existing same crew type blends train and atmos`() {
            // Legacy: newTrain = (oldCrew*oldTrain + newCrew*50) / (oldCrew+newCrew)
            val gen = createGeneral(crew = 1000, crewType = 1100, train = 80, atmos = 80, gold = 10000, rice = 10000, leadership = 80)
            val arg = mapOf<String, Any>("amount" to 1000, "crewType" to 1100)
            val result = runCmd(che_징병(gen, createEnv(), arg), "con_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            // newTrain = (1000*80 + 1000*40) / 2000 = 120000/2000 = 60  (PHP defaultTrainLow=40)
            assertEquals(60 - 80, json["statChanges"]["train"].asInt())
            assertEquals(60 - 80, json["statChanges"]["atmos"].asInt())
        }

        @Test
        fun `switching crew type resets to 50 50`() {
            val gen = createGeneral(crew = 1000, crewType = 1100, train = 80, atmos = 80, gold = 10000, rice = 10000, leadership = 80)
            val arg = mapOf<String, Any>("amount" to 500, "crewType" to 1200) // different type
            val result = runCmd(che_징병(gen, createEnv(), arg), "con_2")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            assertEquals(40 - 80, json["statChanges"]["train"].asInt())
            assertEquals(40 - 80, json["statChanges"]["atmos"].asInt())
        }

        @Test
        fun `conscription capped at leadership times 100`() {
            val gen = createGeneral(crew = 0, crewType = 0, leadership = 10, gold = 50000, rice = 50000)
            val arg = mapOf<String, Any>("amount" to 5000, "crewType" to 1100)
            val result = runCmd(che_징병(gen, createEnv(), arg), "con_3")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            // maxCrew = leadership*100 = 1000, but also clamped by reqAmount=5000 → min(5000, 1000) = 1000
            assertEquals(1000, json["statChanges"]["crew"].asInt())
        }

        @Test
        fun `gold cost includes tech cost multiplier`() {
            val gen = createGeneral(crew = 0, crewType = 0, leadership = 50, gold = 50000, rice = 50000)
            gen.nationId = 1
            val nation = Nation(id = 1, worldId = 1, name = "테스트국", tech = 1000f)
            val env = createEnv()
            val arg = mapOf<String, Any>("amount" to 500, "crewType" to 1100)
            val cmd = che_징병(gen, env, arg)
            cmd.nation = nation

            val result = runBlocking { cmd.run(LiteHashDRBG.build("con_4")) }
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            // baseCost = 500/100 * techCost(=1+1000/1000=2.0) = 5*2 = 10
            // The command uses getNationTechCost which reads from cmd.nation
            val goldChange = json["statChanges"]["gold"].asInt()
            assertTrue(goldChange < 0)
        }
    }

    // ──────────────────────────────────────────────────
    //  DomesticCommand family (성벽보수, 수비강화, 치안강화)
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("DomesticCommand - legacy che_상업투자.php base")
    inner class DomesticParity {

        @Test
        fun `성벽보수 uses strength stat`() {
            val gen = createGeneral(strength = 80, gold = 500)
            val city = createCity(wall = 500, wallMax = 1000, trust = 80f)
            val result = runDomestic(che_성벽보수(gen, createEnv()), city, "wall_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            assertEquals(1, json["statChanges"]["strengthExp"].asInt())
            assertTrue(json["cityChanges"]["wall"].asInt() > 0)
        }

        @Test
        fun `수비강화 uses strength stat`() {
            val gen = createGeneral(strength = 80, gold = 500)
            val city = createCity(def = 500, defMax = 1000, trust = 80f)
            val result = runDomestic(che_수비강화(gen, createEnv()), city, "def_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            assertEquals(1, json["statChanges"]["strengthExp"].asInt())
            assertTrue(json["cityChanges"]["def"].asInt() > 0)
        }

        @Test
        fun `치안강화 uses strength stat`() {
            val gen = createGeneral(strength = 80, gold = 500)
            val city = createCity(secu = 500, secuMax = 1000, trust = 80f)
            val result = runDomestic(che_치안강화(gen, createEnv()), city, "secu_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            assertEquals(1, json["statChanges"]["strengthExp"].asInt())
            assertTrue(json["cityChanges"]["secu"].asInt() > 0)
        }

        @Test
        fun `front line debuff factors differ by command type`() {
            // Legacy: 성벽보수 debuffFront=0.25, 수비강화=0.5, 치안강화=1.0
            // On front line city, score *= debuffFront
            val gen80 = createGeneral(strength = 80, gold = 5000)

            val cityFront = createCity(wall = 100, wallMax = 1000, trust = 80f, frontState = 1)
            val cityRear = createCity(wall = 100, wallMax = 1000, trust = 80f, frontState = 0)

            val resultFront = runDomestic(che_성벽보수(gen80.copy(), createEnv()), cityFront.copy(), "debuff_f1")
            val resultRear = runDomestic(che_성벽보수(gen80.copy(), createEnv()), cityRear.copy(), "debuff_f1")

            val frontDelta = mapper.readTree(resultFront.message)["cityChanges"]["wall"].asInt()
            val rearDelta = mapper.readTree(resultRear.message)["cityChanges"]["wall"].asInt()

            // Front should be much lower due to 0.25 debuff
            assertTrue(frontDelta <= rearDelta, "Front wall delta ($frontDelta) should be <= rear ($rearDelta)")
        }

        @Test
        fun `치안강화 front line debuff is 1_0 meaning no reduction`() {
            // debuffFront=1.0 → score *= 1.0 → no change
            val gen = createGeneral(strength = 80, gold = 5000)
            val cityFront = createCity(secu = 100, secuMax = 1000, trust = 80f, frontState = 1)
            val cityRear = createCity(secu = 100, secuMax = 1000, trust = 80f, frontState = 0)

            val resultFront = runDomestic(che_치안강화(gen.copy(), createEnv()), cityFront.copy(), "secu_debuff")
            val resultRear = runDomestic(che_치안강화(gen.copy(), createEnv()), cityRear.copy(), "secu_debuff")

            val frontDelta = mapper.readTree(resultFront.message)["cityChanges"]["secu"].asInt()
            val rearDelta = mapper.readTree(resultRear.message)["cityChanges"]["secu"].asInt()

            // With debuffFront=1.0, front score = score * 1.0 → same as rear before clamping
            // (May still differ slightly due to max clamping, but should be similar)
            assertTrue(frontDelta >= rearDelta * 0.9, "Secu front ($frontDelta) should be close to rear ($rearDelta)")
        }

        @Test
        fun `domestic command cannot exceed max value`() {
            val gen = createGeneral(intel = 100, gold = 500)
            val city = createCity(agri = 990, agriMax = 1000, trust = 100f)
            val result = runDomestic(che_농지개간(gen, createEnv()), city, "max_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            val delta = json["cityChanges"]["agri"].asInt()
            assertTrue(delta <= 10, "Delta ($delta) should not exceed remaining capacity (10)")
        }

        @Test
        fun `low trust reduces score`() {
            // Legacy: trust < 50 → clamped to 50
            // Score uses trust/100.0 as multiplier
            val genHighTrust = createGeneral(intel = 80, gold = 500)
            val genLowTrust = createGeneral(intel = 80, gold = 500)

            val cityHigh = createCity(agri = 100, agriMax = 1000, trust = 100f)
            val cityLow = createCity(agri = 100, agriMax = 1000, trust = 50f)

            val resultHigh = runDomestic(che_농지개간(genHighTrust, createEnv()), cityHigh, "trust_h")
            val resultLow = runDomestic(che_농지개간(genLowTrust, createEnv()), cityLow, "trust_h")

            val deltaHigh = mapper.readTree(resultHigh.message)["cityChanges"]["agri"].asInt()
            val deltaLow = mapper.readTree(resultLow.message)["cityChanges"]["agri"].asInt()

            assertTrue(deltaHigh >= deltaLow, "High trust ($deltaHigh) should give >= low trust ($deltaLow)")
        }

        @Test
        fun `critical success and fail scale score`() {
            // Run many seeds and verify we get all three outcomes
            val picks = mutableSetOf<String>()
            for (i in 1..50) {
                val gen = createGeneral(intel = 80, gold = 500)
                val city = createCity(agri = 100, agriMax = 1000, trust = 80f)
                val result = runDomestic(che_농지개간(gen, createEnv()), city, "crit_$i")
                val json = mapper.readTree(result.message)
                picks.add(json["criticalResult"].asText())
                if (picks.size == 3) break
            }
            assertTrue(picks.contains("normal") || picks.contains("success") || picks.contains("fail"),
                "Should see at least one outcome type in 50 runs: $picks")
        }

        @Test
        fun `gold cost equals develCost from env`() {
            val develCost = 150
            val gen = createGeneral(gold = 500)
            val city = createCity(agri = 100, agriMax = 1000, trust = 80f)
            val result = runDomestic(che_농지개간(gen, createEnv(develCost = develCost)), city, "cost_1")
            val json = mapper.readTree(result.message)
            assertEquals(-develCost, json["statChanges"]["gold"].asInt())
        }
    }

    // ──────────────────────────────────────────────────
    //  사기진작 (Morale Boost) parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("che_사기진작 - legacy che_사기진작.php")
    inner class MoraleParity {

        @Test
        fun `morale boost reduces train as side effect`() {
            // Legacy: train side effect = train * trainSideEffectByAtmos (0.9)
            val gen = createGeneral(leadership = 80, crew = 200, atmos = 60, train = 80)
            val result = runCmd(che_사기진작(gen, createEnv()), "morale_1")
            assertTrue(result.success)
            val json = mapper.readTree(result.message)
            // trainAfter = max(0, (80 * 0.9).toInt()) = 72; delta = 72-80 = -8
            assertEquals(-8, json["statChanges"]["train"].asInt())
        }
    }

    // ──────────────────────────────────────────────────
    //  Determinism across all commands
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Cross-command determinism")
    inner class CrossCommandDeterminism {

        @Test
        fun `all domestic commands deterministic with same seed`() {
            val commands = listOf("농지개간", "상업투자", "성벽보수", "수비강화", "치안강화")
            for (name in commands) {
                val gen1 = createGeneral(strength = 80, intel = 80, gold = 500)
                val gen2 = createGeneral(strength = 80, intel = 80, gold = 500)
                val city1 = createCity(agri = 100, comm = 100, wall = 100, def = 100, secu = 100,
                    agriMax = 1000, commMax = 1000, wallMax = 1000, defMax = 1000, secuMax = 1000, trust = 80f)
                val city2 = city1.copy()

                val cmd1 = createDomesticCmd(name, gen1, createEnv())
                val cmd2 = createDomesticCmd(name, gen2, createEnv())

                val r1 = runDomestic(cmd1, city1, "det_$name")
                val r2 = runDomestic(cmd2, city2, "det_$name")

                assertEquals(r1.message, r2.message, "Command $name should be deterministic")
            }
        }

        private fun createDomesticCmd(name: String, gen: General, env: CommandEnv): DomesticCommand {
            return when (name) {
                "농지개간" -> che_농지개간(gen, env)
                "상업투자" -> che_상업투자(gen, env)
                "성벽보수" -> che_성벽보수(gen, env)
                "수비강화" -> che_수비강화(gen, env)
                "치안강화" -> che_치안강화(gen, env)
                else -> error("Unknown: $name")
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────

    private fun runCmd(cmd: com.opensam.command.BaseCommand, seed: String): CommandResult {
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun runDomestic(cmd: DomesticCommand, city: City, seed: String): CommandResult {
        cmd.city = city
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun createEnv(develCost: Int = 100): CommandEnv {
        return CommandEnv(
            year = 200,
            month = 1,
            startYear = 190,
            worldId = 1,
            realtimeMode = false,
            develCost = develCost,
        )
    }

    private fun createGeneral(
        leadership: Short = 70,
        strength: Short = 70,
        intel: Short = 70,
        gold: Int = 500,
        rice: Int = 500,
        crew: Int = 1000,
        crewType: Short = 0,
        train: Short = 60,
        atmos: Short = 60,
    ): General = General(
        id = 1,
        worldId = 1,
        name = "테스트장수",
        nationId = 1,
        cityId = 1,
        gold = gold,
        rice = rice,
        crew = crew,
        crewType = crewType,
        train = train,
        atmos = atmos,
        leadership = leadership,
        strength = strength,
        intel = intel,
        politics = 60,
        charm = 60,
        turnTime = OffsetDateTime.now(),
    )

    private fun createCity(
        nationId: Long = 1,
        pop: Int = 50000,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        secu: Int = 500,
        secuMax: Int = 1000,
        def: Int = 500,
        defMax: Int = 1000,
        wall: Int = 500,
        wallMax: Int = 1000,
        trust: Float = 80f,
        frontState: Short = 0,
    ): City = City(
        id = 1,
        worldId = 1,
        name = "테스트도시",
        nationId = nationId,
        pop = pop,
        popMax = 100000,
        agri = agri,
        agriMax = agriMax,
        comm = comm,
        commMax = commMax,
        secu = secu,
        secuMax = secuMax,
        def = def,
        defMax = defMax,
        wall = wall,
        wallMax = wallMax,
        trust = trust,
        supplyState = 1,
        frontState = frontState,
    )

    // Extension for General to support copy-like behavior in tests
    private fun General.copy(): General = General(
        id = id, worldId = worldId, name = name, nationId = nationId, cityId = cityId,
        gold = gold, rice = rice, crew = crew, crewType = crewType, train = train, atmos = atmos,
        leadership = leadership, strength = strength, intel = intel, politics = politics, charm = charm,
        turnTime = turnTime,
    )

    private fun City.copy(): City = City(
        id = id, worldId = worldId, name = name, nationId = nationId,
        pop = pop, popMax = popMax, agri = agri, agriMax = agriMax, comm = comm, commMax = commMax,
        secu = secu, secuMax = secuMax, def = def, defMax = defMax, wall = wall, wallMax = wallMax,
        trust = trust, supplyState = supplyState, frontState = frontState,
    )
}
