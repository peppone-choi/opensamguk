package com.opensam.command

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.opensam.command.general.che_농지개간
import com.opensam.command.general.che_단련
import com.opensam.command.general.che_사기진작
import com.opensam.command.general.che_상업투자
import com.opensam.command.general.che_징병
import com.opensam.command.general.che_훈련
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.engine.LiteHashDRBG
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.math.roundToInt

class CommandParityTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `che_훈련 parity and determinism`() {
        val general = createGeneral(leadership = 80, crew = 200, train = 60, atmos = 70)
        val city = createCity(nationId = 1)
        val env = createEnv()

        val first = runTraining(general, city, env, "test_cmd_1")
        val second = runTraining(general, city, env, "test_cmd_1")

        assertTrue(first.success)
        assertEquals(first.message, second.message)

        val json = mapper.readTree(first.message)
        assertEquals(2, json["statChanges"]["train"].asInt())
        assertEquals(-7, json["statChanges"]["atmos"].asInt())
        assertEquals(100, json["statChanges"]["experience"].asInt())
        assertEquals(70, json["statChanges"]["dedication"].asInt())
    }

    @Test
    fun `che_징병 parity and determinism`() {
        val general = createGeneral(leadership = 50, crew = 0, crewType = 0, train = 0, atmos = 0)
        val city = createCity(nationId = 1, pop = 10000)
        val env = createEnv()
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)

        val first = runConscription(general, city, env, arg, "test_cmd_2")
        val second = runConscription(general, city, env, arg, "test_cmd_2")

        assertTrue(first.success)
        assertEquals(first.message, second.message)

        val json = mapper.readTree(first.message)
        assertEquals(500, json["statChanges"]["crew"].asInt())
        assertEquals(0, json["statChanges"]["crewType"].asInt())
        assertEquals(40, json["statChanges"]["train"].asInt())
        assertEquals(40, json["statChanges"]["atmos"].asInt())
        assertEquals(-50, json["statChanges"]["gold"].asInt())
        assertEquals(-5, json["statChanges"]["rice"].asInt())
        assertEquals(-500, json["cityChanges"]["pop"].asInt())
    }

    @Test
    fun `che_농지개간 parity and determinism`() {
        val general = createGeneral(intel = 80, gold = 500)
        val city = createCity(nationId = 1, trust = 80f, agri = 500, agriMax = 1000, frontState = 0)
        val env = createEnv(develCost = 100)

        val expected = expectedDomesticDelta(stat = 80, trust = 80f, current = 500, max = 1000, frontState = 0, seed = "test_cmd_3")

        val first = runAgri(general, city, env, "test_cmd_3")
        val second = runAgri(general, city, env, "test_cmd_3")

        assertTrue(first.success)
        assertEquals(first.message, second.message)

        val json = mapper.readTree(first.message)
        assertEquals(-100, json["statChanges"]["gold"].asInt())
        assertEquals(expected.exp, json["statChanges"]["experience"].asInt())
        assertEquals(expected.score, json["statChanges"]["dedication"].asInt())
        assertEquals(1, json["statChanges"]["intelExp"].asInt())
        assertEquals(expected.delta, json["cityChanges"]["agri"].asInt())
        assertEquals(expected.pick, json["criticalResult"].asText())
    }

    @Test
    fun `che_상업투자 parity and determinism`() {
        val general = createGeneral(intel = 75, gold = 500)
        val city = createCity(nationId = 1, trust = 90f, comm = 450, commMax = 1000, frontState = 0)
        val env = createEnv(develCost = 120)

        val expected = expectedDomesticDelta(stat = 75, trust = 90f, current = 450, max = 1000, frontState = 0, seed = "test_cmd_4")

        val first = runCommerce(general, city, env, "test_cmd_4")
        val second = runCommerce(general, city, env, "test_cmd_4")

        assertTrue(first.success)
        assertEquals(first.message, second.message)

        val json = mapper.readTree(first.message)
        assertEquals(-120, json["statChanges"]["gold"].asInt())
        assertEquals(expected.exp, json["statChanges"]["experience"].asInt())
        assertEquals(expected.score, json["statChanges"]["dedication"].asInt())
        assertEquals(1, json["statChanges"]["intelExp"].asInt())
        assertEquals(expected.delta, json["cityChanges"]["comm"].asInt())
        assertEquals(expected.pick, json["criticalResult"].asText())
    }

    @Test
    fun `che_사기진작 parity and determinism`() {
        val general = createGeneral(leadership = 80, crew = 200, atmos = 70, train = 80, gold = 1000)
        val city = createCity(nationId = 1)
        val env = createEnv()

        val first = runMorale(general, city, env, "test_cmd_5")
        val second = runMorale(general, city, env, "test_cmd_5")

        assertTrue(first.success)
        assertEquals(first.message, second.message)

        val json = mapper.readTree(first.message)
        assertEquals(-2, json["statChanges"]["gold"].asInt())
        assertEquals(2, json["statChanges"]["atmos"].asInt())
        assertEquals(-8, json["statChanges"]["train"].asInt())
        assertEquals(100, json["statChanges"]["experience"].asInt())
        assertEquals(70, json["statChanges"]["dedication"].asInt())
    }

    @Test
    fun `che_단련 parity and determinism`() {
        val general = createGeneral(
            leadership = 60,
            strength = 70,
            intel = 50,
            crew = 1000,
            train = 60,
            atmos = 70,
            gold = 1000,
            rice = 1000,
        )
        val env = createEnv(develCost = 120)

        val expected = expectedDrill(
            crew = 1000,
            train = 60,
            atmos = 70,
            leadership = 60,
            strength = 70,
            intel = 50,
            seed = "test_cmd_6",
        )

        val first = runDrill(general, env, "test_cmd_6")
        val second = runDrill(general, env, "test_cmd_6")

        assertTrue(first.success)
        assertEquals(first.message, second.message)

        val json = mapper.readTree(first.message)
        assertEquals(-120, json["statChanges"]["gold"].asInt())
        assertEquals(-120, json["statChanges"]["rice"].asInt())
        assertEquals(2, json["statChanges"]["experience"].asInt())
        assertEquals(1, json["statChanges"][expected.incStat]!!.asInt())
        assertEquals(0, json["dexChanges"]["crewType"].asInt())
        assertEquals(expected.score, json["dexChanges"]["amount"].asInt())
        assertEquals(expected.pick, json["criticalResult"].asText())
    }

    private fun runTraining(general: General, city: City, env: CommandEnv, seed: String): CommandResult {
        val cmd = che_훈련(general, env)
        cmd.city = city
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun runConscription(general: General, city: City, env: CommandEnv, arg: Map<String, Any>, seed: String): CommandResult {
        val cmd = che_징병(general, env, arg)
        cmd.city = city
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun runAgri(general: General, city: City, env: CommandEnv, seed: String): CommandResult {
        val cmd = che_농지개간(general, env)
        cmd.city = city
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun runCommerce(general: General, city: City, env: CommandEnv, seed: String): CommandResult {
        val cmd = che_상업투자(general, env)
        cmd.city = city
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun runMorale(general: General, city: City, env: CommandEnv, seed: String): CommandResult {
        val cmd = che_사기진작(general, env)
        cmd.city = city
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun runDrill(general: General, env: CommandEnv, seed: String): CommandResult {
        val cmd = che_단련(general, env)
        return runBlocking { cmd.run(LiteHashDRBG.build(seed)) }
    }

    private fun expectedDomesticDelta(
        stat: Int,
        trust: Float,
        current: Int,
        max: Int,
        frontState: Int,
        seed: String,
    ): DomesticExpected {
        val rng = LiteHashDRBG.build(seed)
        val trustApplied = maxOf(50f, trust).toDouble()
        var score = (stat * (trustApplied / 100.0) * (0.8 + rng.nextDouble() * 0.4)).toInt()
        score = maxOf(1, score)

        val successRatio = minOf(1.0, 0.1 * (trustApplied / 80.0))
        val failRatio = minOf(1.0 - successRatio, 0.1)
        val roll = rng.nextDouble()

        val pick = when {
            roll < failRatio -> {
                score = (score * 0.5).toInt()
                "fail"
            }

            roll < failRatio + successRatio -> {
                score = (score * 1.5).toInt()
                "success"
            }

            else -> "normal"
        }

        score = maxOf(1, score)
        if (frontState == 1 || frontState == 3) {
            score = (score * 0.5).toInt()
        }

        val newValue = minOf(max, current + score)
        val delta = newValue - current
        val exp = (score * 0.7).toInt()
        return DomesticExpected(delta = delta, score = score, exp = exp, pick = pick)
    }

    private fun expectedDrill(
        crew: Int,
        train: Int,
        atmos: Int,
        leadership: Int,
        strength: Int,
        intel: Int,
        seed: String,
    ): DrillExpected {
        val rng = LiteHashDRBG.build(seed)
        val criticalRoll = rng.nextDouble()
        val pick: String
        val multiplier: Int
        when {
            criticalRoll < 0.33 -> {
                pick = "fail"
                multiplier = 1
            }

            criticalRoll > 0.66 -> {
                pick = "success"
                multiplier = 3
            }

            else -> {
                pick = "normal"
                multiplier = 2
            }
        }

        val baseScore = (crew.toDouble() * train * atmos) / 200000.0
        val score = (baseScore * multiplier).roundToInt()

        val totalWeight = leadership + strength + intel
        var roll = rng.nextDouble() * totalWeight
        val incStat = when {
            run {
                roll -= leadership
                roll < 0
            } -> "leadershipExp"

            run {
                roll -= strength
                roll < 0
            } -> "strengthExp"

            else -> "intelExp"
        }

        return DrillExpected(score = score, pick = pick, incStat = incStat)
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
    ): General {
        return General(
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
    }

    private fun createCity(
        nationId: Long,
        pop: Int = 10000,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        trust: Float = 80f,
        frontState: Short = 0,
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            pop = pop,
            popMax = 50000,
            agri = agri,
            agriMax = agriMax,
            comm = comm,
            commMax = commMax,
            secu = 500,
            secuMax = 1000,
            def = 500,
            defMax = 1000,
            wall = 500,
            wallMax = 1000,
            trust = trust,
            supplyState = 1,
            frontState = frontState,
        )
    }

    private data class DomesticExpected(
        val delta: Int,
        val score: Int,
        val exp: Int,
        val pick: String,
    )

    private data class DrillExpected(
        val score: Int,
        val pick: String,
        val incStat: String,
    )
}
