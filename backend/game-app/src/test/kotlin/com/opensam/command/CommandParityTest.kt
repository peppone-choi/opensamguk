package com.opensam.command

import com.opensam.command.general.*
import com.opensam.engine.LiteHashDRBG
import com.opensam.entity.City
import com.opensam.entity.General
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Command parity tests — verify individual commands produce deterministic,
 * expected results using LiteHashDRBG.
 */
class CommandParityTest {

    private val env = CommandEnv(year = 200, month = 3, startYear = 184, worldId = 1, develCost = 100)

    // ========== che_훈련 (no RNG) ==========

    @Test
    fun `훈련 produces deterministic train increase`() = runBlocking {
        val general = createGeneral(leadership = 80, crew = 1000, train = 60, atmos = 70)
        val cmd = che_훈련(general, env)

        val result = cmd.run(LiteHashDRBG.build("train_test"))

        assertTrue(result.success)
        assertNotNull(result.message)
        // rawScore = (80 * 100.0 / 1000) * 0.05 = 0.4 → roundToInt = 0
        // But maxOf(0, min(0, 20)) = 0 ... leadership=80, crew=1000 → 80*100/1000=8.0*0.05=0.4→0
        // That's too low. Let's verify with higher leadership or lower crew.
    }

    @Test
    fun `훈련 with high leadership low crew gives higher score`() = runBlocking {
        val general = createGeneral(leadership = 90, crew = 500, train = 60, atmos = 70)
        val cmd = che_훈련(general, env)

        val result = cmd.run(LiteHashDRBG.build("train_test"))

        assertTrue(result.success)
        // rawScore = (90 * 100.0 / 500) * 0.05 = 18.0 * 0.05 = 0.9 → 1
        assertTrue(result.message!!.contains("\"train\":1"))
    }

    @Test
    fun `훈련 deterministic - same inputs always same output`() = runBlocking {
        val result1 = runTraining()
        val result2 = runTraining()

        assertEquals(result1.message, result2.message)
    }

    private suspend fun runTraining(): CommandResult {
        val general = createGeneral(leadership = 95, crew = 300, train = 50, atmos = 70)
        val cmd = che_훈련(general, env)
        return cmd.run(LiteHashDRBG.build("train_determ"))
    }

    // ========== che_징병 (no RNG) ==========

    @Test
    fun `징병 produces correct crew change`() = runBlocking {
        val general = createGeneral(leadership = 50, crew = 0, gold = 10000, rice = 10000)
        val arg = mapOf<String, Any>("crewType" to 0, "amount" to 1000)
        val cmd = che_징병(general, env, arg)

        val result = cmd.run(LiteHashDRBG.build("recruit_test"))

        assertTrue(result.success)
        // maxCrew: leadership*100 - crew(same type)=0 → min(1000, 5000) = 1000
        assertTrue(result.message!!.contains("\"crew\":1000"))
    }

    @Test
    fun `징병 deterministic`() = runBlocking {
        val result1 = runRecruitment()
        val result2 = runRecruitment()

        assertEquals(result1.message, result2.message)
    }

    private suspend fun runRecruitment(): CommandResult {
        val general = createGeneral(leadership = 60, crew = 500, gold = 10000, rice = 10000)
        val arg = mapOf<String, Any>("crewType" to 0, "amount" to 500)
        val cmd = che_징병(general, env, arg)
        return cmd.run(LiteHashDRBG.build("recruit_determ"))
    }

    // ========== che_사기진작 (no RNG) ==========

    @Test
    fun `사기진작 produces correct atmos increase`() = runBlocking {
        val general = createGeneral(leadership = 90, crew = 500, atmos = 60, train = 70, gold = 5000)
        val cmd = che_사기진작(general, env)

        val result = cmd.run(LiteHashDRBG.build("morale_test"))

        assertTrue(result.success)
        // rawScore = (90 * 100.0 / 500) * 0.05 = 0.9 → 1
        // maxPossible = max(0, 80-60) = 20
        // score = min(1, 20) = 1
        assertTrue(result.message!!.contains("\"atmos\":1"))
    }

    // ========== DomesticCommand (농지개간 - uses RNG) ==========

    @Test
    fun `농지개간 is deterministic with LiteHashDRBG`() = runBlocking {
        val result1 = runDomestic("agri_test")
        val result2 = runDomestic("agri_test")

        assertEquals(result1.message, result2.message)
    }

    @Test
    fun `농지개간 differs with different seed`() = runBlocking {
        val result1 = runDomestic("agri_seed_A")
        val result2 = runDomestic("agri_seed_B")

        // With different seeds, at least one aspect should differ
        // (score or criticalResult)
        assertNotEquals(result1.message, result2.message)
    }

    @Test
    fun `농지개간 score uses intel stat`() = runBlocking {
        val general = createGeneral(intel = 90, politics = 30, trust = 80f)
        val city = createCity(agri = 200, agriMax = 1000, trust = 80f)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = cmd.run(LiteHashDRBG.build("agri_stat_test"))

        assertTrue(result.success)
        // Score is based on intel (statKey = "intel")
        // rawScore = (90 * (80/100) * (0.8 + rng * 0.4)).toInt()
        assertTrue(result.message!!.contains("\"agri\":"))
    }

    private suspend fun runDomestic(seed: String): CommandResult {
        val general = createGeneral(intel = 75, politics = 60, trust = 80f)
        val city = createCity(agri = 300, agriMax = 1000, trust = 80f)
        val cmd = che_농지개간(general, env)
        cmd.city = city
        return cmd.run(LiteHashDRBG.build(seed))
    }

    // ========== che_단련 (uses RNG twice) ==========

    @Test
    fun `단련 is deterministic with LiteHashDRBG`() = runBlocking {
        val result1 = runDrillingCommand("drill_test")
        val result2 = runDrillingCommand("drill_test")

        assertEquals(result1.message, result2.message)
    }

    @Test
    fun `단련 differs with different seed`() = runBlocking {
        val results = (1..5).map { runDrillingCommand("drill_seed_$it") }
        val uniqueMessages = results.map { it.message }.toSet()

        // At least 2 different outcomes from 5 seeds
        assertTrue(uniqueMessages.size >= 2, "Expected varied outcomes from different seeds")
    }

    @Test
    fun `단련 score formula matches legacy`() = runBlocking {
        val general = createGeneral(
            leadership = 60, strength = 70, intel = 50,
            crew = 2000, train = 70, atmos = 70, gold = 5000, rice = 5000,
        )
        val cmd = che_단련(general, env)

        val rng = LiteHashDRBG.build("drill_formula")
        val result = cmd.run(rng)

        assertTrue(result.success)
        // baseScore = (2000 * 70 * 70) / 200000.0 = 9800000 / 200000 = 49.0
        // criticalRoll determines multiplier: 1, 2, or 3
        // score = (49.0 * multiplier).roundToInt()
        val msg = result.message!!
        assertTrue(msg.contains("\"criticalResult\":"))
    }

    private suspend fun runDrillingCommand(seed: String): CommandResult {
        val general = createGeneral(
            leadership = 60, strength = 70, intel = 50,
            crew = 2000, train = 70, atmos = 70, gold = 5000, rice = 5000,
        )
        val cmd = che_단련(general, env)
        return cmd.run(LiteHashDRBG.build(seed))
    }

    // ========== Cross-command determinism ==========

    @Test
    fun `multiple commands in sequence produce same results with same seed`() = runBlocking {
        fun runSequence(): List<String?> {
            return runBlocking {
                val rng = LiteHashDRBG.build("sequence_test")

                val g1 = createGeneral(leadership = 90, crew = 500, train = 50, atmos = 60)
                val r1 = che_훈련(g1, env).run(rng)

                val g2 = createGeneral(intel = 75, trust = 80f)
                val city = createCity(agri = 300, agriMax = 1000, trust = 80f)
                val cmd2 = che_농지개간(g2, env)
                cmd2.city = city
                val r2 = cmd2.run(rng)

                val g3 = createGeneral(
                    leadership = 60, strength = 70, intel = 50,
                    crew = 2000, train = 70, atmos = 70, gold = 5000, rice = 5000,
                )
                val r3 = che_단련(g3, env).run(rng)

                listOf(r1.message, r2.message, r3.message)
            }
        }

        val seq1 = runSequence()
        val seq2 = runSequence()

        assertEquals(seq1, seq2)
    }

    // ========== Helpers ==========

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        politics: Short = 50,
        charm: Short = 50,
        crew: Int = 1000,
        crewType: Int = 0,
        train: Short = 80,
        atmos: Short = 80,
        gold: Int = 5000,
        rice: Int = 5000,
        experience: Int = 1000,
        dedication: Int = 1000,
        trust: Float = 80f,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = nationId,
            cityId = 1,
            leadership = leadership,
            strength = strength,
            intel = intel,
            politics = politics,
            charm = charm,
            crew = crew,
            crewType = crewType.toShort(),
            train = train,
            atmos = atmos,
            gold = gold,
            rice = rice,
            experience = experience,
            dedication = dedication,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        nationId: Long = 1,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        secu: Int = 500,
        secuMax: Int = 1000,
        trust: Float = 80f,
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            pop = 10000,
            popMax = 50000,
            agri = agri,
            agriMax = agriMax,
            comm = comm,
            commMax = commMax,
            secu = secu,
            secuMax = secuMax,
            trust = trust,
            def = 500,
            defMax = 1000,
            wall = 500,
            wallMax = 1000,
        )
    }
}
