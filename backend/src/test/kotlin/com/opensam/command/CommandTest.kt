package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.general.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class CommandTest {

    private fun createTestGeneral(
        gold: Int = 1000,
        rice: Int = 1000,
        crew: Int = 0,
        crewType: Short = 0,
        train: Short = 0,
        atmos: Short = 0,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        politics: Short = 50,
        charm: Short = 50,
        nationId: Long = 1,
        cityId: Long = 1,
        officerLevel: Short = 0,
        troopId: Long = 0,
        experience: Int = 0,
        dedication: Int = 0,
        betray: Short = 0,
    ): General {
        return General(
            id = 1,
            worldId = 1,
            name = "테스트장수",
            nationId = nationId,
            cityId = cityId,
            gold = gold,
            rice = rice,
            crew = crew,
            crewType = crewType,
            train = train,
            atmos = atmos,
            leadership = leadership,
            strength = strength,
            intel = intel,
            politics = politics,
            charm = charm,
            officerLevel = officerLevel,
            troopId = troopId,
            experience = experience,
            dedication = dedication,
            betray = betray,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createTestCity(
        nationId: Long = 1,
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
        pop: Int = 10000,
        popMax: Int = 50000,
        trust: Int = 80,
        supplyState: Short = 1,
        frontState: Short = 0,
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
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
            pop = pop,
            popMax = popMax,
            trust = trust,
            supplyState = supplyState,
            frontState = frontState,
        )
    }

    private fun createTestNation(
        id: Long = 1,
        level: Short = 1,
        gold: Int = 10000,
        rice: Int = 10000,
    ): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "테스트국가",
            color = "#FF0000",
            gold = gold,
            rice = rice,
            level = level,
        )
    }

    private fun createTestEnv(
        year: Int = 200,
        month: Int = 1,
        startYear: Int = 190,
    ) = CommandEnv(
        year = year,
        month = month,
        startYear = startYear,
        worldId = 1,
        realtimeMode = false,
    )

    private val fixedRng = Random(42)

    // ========== 휴식 (Rest) ==========

    @Test
    fun `휴식 command should succeed for any general`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = 휴식(general, env)
        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("휴식"))
    }

    @Test
    fun `휴식 should have zero cost`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = 휴식(general, env)
        val cost = cmd.getCost()

        assertEquals(0, cost.gold)
        assertEquals(0, cost.rice)
    }

    @Test
    fun `휴식 should have zero pre and post req turns`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = 휴식(general, env)

        assertEquals(0, cmd.getPreReqTurn())
        assertEquals(0, cmd.getPostReqTurn())
        assertEquals(0, cmd.getDuration())
    }

    @Test
    fun `휴식 should have no constraints`() {
        val general = createTestGeneral(nationId = 0)
        val env = createTestEnv()
        val cmd = 휴식(general, env)

        val fullResult = cmd.checkFullCondition()
        assertTrue(fullResult is ConstraintResult.Pass)
    }

    // ========== 농지개간 (Farming) ==========

    @Test
    fun `농지개간 should succeed when constraints pass`() {
        val general = createTestGeneral(gold = 1000, intel = 80)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("농지 개간"))
    }

    @Test
    fun `농지개간 should use intel stat for calculation`() {
        val general = createTestGeneral(intel = 90)
        val env = createTestEnv()
        val cmd = che_농지개간(general, env)

        assertEquals("agri", cmd.cityKey)
        assertEquals("intel", cmd.statKey)
    }

    @Test
    fun `농지개간 should fail when general has no nation`() {
        val general = createTestGeneral(nationId = 0)
        val env = createTestEnv()
        val city = createTestCity(nationId = 0)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("소속 국가"))
    }

    @Test
    fun `농지개간 should fail when city is at max agri`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val city = createTestCity(agri = 1000, agriMax = 1000)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("최대치"))
    }

    @Test
    fun `농지개간 should fail when city is not supplied`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val city = createTestCity(supplyState = 0)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("보급"))
    }

    @Test
    fun `농지개간 cost should use env develCost`() {
        val general = createTestGeneral()
        val env = CommandEnv(year = 200, month = 1, startYear = 190, worldId = 1, develCost = 150)
        val cmd = che_농지개간(general, env)
        val cost = cmd.getCost()

        assertEquals(150, cost.gold)
        assertEquals(0, cost.rice)
    }

    @Test
    fun `농지개간 should fail when general lacks gold`() {
        val general = createTestGeneral(gold = 50)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("자금"))
    }

    @Test
    fun `농지개간 should apply front line debuff`() {
        val general = createTestGeneral(intel = 80)
        val env = createTestEnv()
        val city = createTestCity(frontState = 1, trust = 100)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(Random(100)) }
        assertTrue(result.success)
        // The score should be reduced due to front line debuff
        assertNotNull(result.message)
    }

    // ========== 상업투자 (Commerce Investment) ==========

    @Test
    fun `상업투자 should target comm stat`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = che_상업투자(general, env)

        assertEquals("comm", cmd.cityKey)
        assertEquals("intel", cmd.statKey)
    }

    // ========== 모병 (Recruitment) ==========

    @Test
    fun `모병 should require gold and rice`() {
        val general = createTestGeneral(gold = 0, rice = 0, leadership = 50)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `모병 should fail for neutral general`() {
        val general = createTestGeneral(nationId = 0)
        val env = createTestEnv()
        val city = createTestCity(nationId = 0)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("소속 국가"))
    }

    @Test
    fun `모병 should succeed with enough resources`() {
        val general = createTestGeneral(gold = 5000, rice = 5000, leadership = 50)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Pass)

        val runResult = runBlocking { cmd.run(fixedRng) }
        assertTrue(runResult.success)
        assertTrue(runResult.logs[0].contains("모병"))
    }

    @Test
    fun `모병 should cap crew at leadership times 100`() {
        val general = createTestGeneral(gold = 50000, rice = 50000, leadership = 10)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("amount" to 99999, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val cost = cmd.getCost()
        // maxCrew should be min(99999, max(0, 10*100 - 0)) = 1000
        // baseCost = 1000/10 = 100, gold = 100*2 = 200
        assertEquals(200, cost.gold)
    }

    @Test
    fun `모병 should calculate additional recruit cost when same crew type`() {
        val general = createTestGeneral(gold = 50000, rice = 50000, leadership = 50, crew = 2000, crewType = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 1)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        // maxCrew = min(500, max(0, 50*100 - 2000)) = min(500, 3000) = 500
        val cost = cmd.getCost()
        assertEquals(100, cost.gold) // 500/10 * 2 = 100
    }

    @Test
    fun `모병 should merge train and atmos when same crew type`() {
        val general = createTestGeneral(
            gold = 50000, rice = 50000, leadership = 50,
            crew = 1000, crewType = 1, train = 80, atmos = 80
        )
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 1)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("추가모병"))
    }

    @Test
    fun `모병 should set default train and atmos when different crew type`() {
        val general = createTestGeneral(
            gold = 50000, rice = 50000, leadership = 50,
            crew = 1000, crewType = 1, train = 80, atmos = 80
        )
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 2)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertFalse(result.logs[0].contains("추가모병"))
    }

    // ========== 건국 (Found Nation) ==========

    @Test
    fun `건국 should fail during opening part`() {
        // startYear=190, year=200 => relYear=10, BeOpeningPart(11) checks relYear < 1 => false => Fail
        val general = createTestGeneral(nationId = 1, officerLevel = 12)
        val env = createTestEnv(year = 200, startYear = 190)
        val nation = createTestNation(level = 0)
        val cmd = 건국(general, env)
        cmd.nation = nation

        val result = cmd.checkFullCondition()
        // BeOpeningPart(relYear+1) = BeOpeningPart(11) => relYear < 1 is false => Fail
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `건국 should check BeLord constraint`() {
        // general with officerLevel < 12 should fail BeLord
        val general = createTestGeneral(nationId = 1, officerLevel = 5)
        val env = createTestEnv(year = 190, startYear = 190)
        val nation = createTestNation(level = 0)
        val cmd = 건국(general, env)
        cmd.nation = nation

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `건국 run should fail in first turn`() {
        // yearMonth = startYear*12 + month <= initYearMonth = startYear*12 + 1
        val general = createTestGeneral(nationId = 1, officerLevel = 12)
        val env = createTestEnv(year = 190, month = 1, startYear = 190)
        val cmd = 건국(general, env)

        val result = runBlocking { cmd.run(fixedRng) }
        assertFalse(result.success)
        assertTrue(result.logs[0].contains("다음 턴부터"))
    }

    @Test
    fun `건국 run should succeed after first turn`() {
        val general = createTestGeneral(nationId = 1, officerLevel = 12, cityId = 5)
        val env = createTestEnv(year = 190, month = 2, startYear = 190)
        val city = createTestCity()
        val cmd = 건국(general, env, mapOf("nationName" to "신한", "nationType" to "군벌"))
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("신한"))
        assertTrue(result.logs[0].contains("건국"))
    }

    // ========== 훈련 (Training) ==========

    @Test
    fun `훈련 should fail without crew`() {
        val general = createTestGeneral(crew = 0)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_훈련(general, env)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("병사"))
    }

    @Test
    fun `훈련 should fail when train is already at max`() {
        val general = createTestGeneral(crew = 1000, train = 80)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_훈련(general, env)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("훈련"))
    }

    @Test
    fun `훈련 should succeed with valid conditions`() {
        val general = createTestGeneral(crew = 1000, train = 50, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_훈련(general, env)
        cmd.city = city

        val condResult = cmd.checkFullCondition()
        assertTrue(condResult is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("훈련치"))
    }

    // ========== 하야 (Resign) ==========

    @Test
    fun `하야 should fail for lord`() {
        val general = createTestGeneral(officerLevel = 12)
        val env = createTestEnv()
        val cmd = 하야(general, env)

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("군주"))
    }

    @Test
    fun `하야 should fail for neutral general`() {
        val general = createTestGeneral(nationId = 0)
        val env = createTestEnv()
        val cmd = 하야(general, env)

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `하야 should succeed for non-lord in a nation`() {
        val general = createTestGeneral(officerLevel = 5, nationId = 1, gold = 5000, rice = 3000)
        val env = createTestEnv()
        val nation = createTestNation()
        val cmd = 하야(general, env)
        cmd.nation = nation

        val condResult = cmd.checkFullCondition()
        assertTrue(condResult is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("하야"))
    }

    @Test
    fun `하야 should increase betray penalty on repeated resignations`() {
        val general = createTestGeneral(officerLevel = 5, nationId = 1, betray = 5, experience = 10000, dedication = 10000)
        val env = createTestEnv()
        val nation = createTestNation()
        val cmd = 하야(general, env)
        cmd.nation = nation

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        // With betray=5, penaltyRate = 0.5, so should lose 50% of exp and ded
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("experience"))
    }

    // ========== 소집해제 (Disband) ==========

    @Test
    fun `소집해제 should fail without crew`() {
        val general = createTestGeneral(crew = 0)
        val env = createTestEnv()
        val cmd = che_소집해제(general, env)

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `소집해제 should succeed with crew`() {
        val general = createTestGeneral(crew = 500)
        val env = createTestEnv()
        val cmd = che_소집해제(general, env)

        val condResult = cmd.checkFullCondition()
        assertTrue(condResult is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("소집해제"))
        // Should return all crew as pop
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("\"pop\":500"))
    }

    // ========== 헌납 (Donate) ==========

    @Test
    fun `헌납 gold should fail without sufficient gold`() {
        val general = createTestGeneral(gold = 50, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("isGold" to true, "amount" to 100)
        val cmd = che_헌납(general, env, arg)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("자금"))
    }

    @Test
    fun `헌납 rice should check rice constraint`() {
        val general = createTestGeneral(rice = 50, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("isGold" to false, "amount" to 100)
        val cmd = che_헌납(general, env, arg)
        cmd.city = city

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("군량"))
    }

    @Test
    fun `헌납 should succeed with sufficient gold`() {
        val general = createTestGeneral(gold = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("isGold" to true, "amount" to 1000)
        val cmd = che_헌납(general, env, arg)
        cmd.city = city

        val condResult = cmd.checkFullCondition()
        assertTrue(condResult is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("금"))
        assertTrue(result.logs[0].contains("헌납"))
    }

    // ========== Date formatting ==========

    @Test
    fun `formatDate should pad month`() {
        val general = createTestGeneral()
        val env = createTestEnv(year = 200, month = 3)
        val cmd = 휴식(general, env)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.logs[0].contains("200년 03월"))
    }
}
