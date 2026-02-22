package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.general.*
import com.opensam.engine.LiteHashDRBG
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class GeneralCivilCommandTest {

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
        injury: Short = 0,
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
            injury = injury,
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
        trust: Float = 80f,
        supplyState: Short = 1,
        frontState: Short = 0,
        trade: Int = 100,
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
            trade = trade,
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
        develCost: Int = 100,
    ) = CommandEnv(
        year = year,
        month = month,
        startYear = startYear,
        worldId = 1,
        realtimeMode = false,
        develCost = develCost,
    )

    private val fixedRng = Random(42)

    @Test
    fun `수비강화 domestic metadata should match spec`() {
        val cmd = che_수비강화(createTestGeneral(), createTestEnv())

        assertEquals("def", cmd.cityKey)
        assertEquals("strength", cmd.statKey)
        assertEquals(0.5, cmd.debuffFront, 0.0001)
    }

    @Test
    fun `수비강화 should fail when city defense is maxed`() {
        val cmd = che_수비강화(createTestGeneral(), createTestEnv())
        cmd.city = createTestCity(def = 1000, defMax = 1000)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `수비강화 should run and return expected message keys`() {
        val cmd = che_수비강화(createTestGeneral(strength = 80), createTestEnv(develCost = 150))
        cmd.city = createTestCity(def = 500, defMax = 1000, trust = 90f)

        val result = runBlocking { cmd.run(LiteHashDRBG.build("defense_boost_seed")) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("수비 강화"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"cityChanges\""))
        assertTrue(msg.contains("\"def\":"))
        assertTrue(msg.contains("\"criticalResult\""))
    }

    @Test
    fun `성벽보수 domestic metadata should match spec`() {
        val cmd = che_성벽보수(createTestGeneral(), createTestEnv())

        assertEquals("wall", cmd.cityKey)
        assertEquals("strength", cmd.statKey)
        assertEquals(0.25, cmd.debuffFront, 0.0001)
    }

    @Test
    fun `성벽보수 should fail when city wall is maxed`() {
        val cmd = che_성벽보수(createTestGeneral(), createTestEnv())
        cmd.city = createTestCity(wall = 1000, wallMax = 1000)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `성벽보수 should run and return expected message keys`() {
        val cmd = che_성벽보수(createTestGeneral(strength = 75), createTestEnv())
        cmd.city = createTestCity(wall = 300, wallMax = 1000, trust = 85f)

        val result = runBlocking { cmd.run(LiteHashDRBG.build("wall_repair_seed")) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("성벽 보수"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"cityChanges\""))
        assertTrue(msg.contains("\"wall\":"))
        assertTrue(msg.contains("\"criticalResult\""))
    }

    @Test
    fun `정착장려 should pass constraints with enough rice and capacity`() {
        val cmd = che_정착장려(createTestGeneral(rice = 1000), createTestEnv(develCost = 100))
        cmd.city = createTestCity(pop = 10000, popMax = 50000)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Pass)
    }

    @Test
    fun `정착장려 should fail constraints when city population is maxed`() {
        val cmd = che_정착장려(createTestGeneral(rice = 1000), createTestEnv())
        cmd.city = createTestCity(pop = 50000, popMax = 50000)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `정착장려 should run successfully with deterministic rng`() {
        val cmd = che_정착장려(createTestGeneral(leadership = 80, rice = 1000), createTestEnv(develCost = 120))
        cmd.city = createTestCity(pop = 10000, popMax = 50000)

        val result = runBlocking { cmd.run(LiteHashDRBG.build("settle_policy_seed")) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("정착 장려"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"cityChanges\""))
        assertTrue(msg.contains("\"pop\":"))
        assertTrue(msg.contains("\"criticalResult\""))
    }

    @Test
    fun `주민선정 should pass constraints when trust is below max`() {
        val cmd = che_주민선정(createTestGeneral(rice = 1000), createTestEnv())
        cmd.city = createTestCity(trust = 99.9f)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Pass)
    }

    @Test
    fun `주민선정 should fail constraints when trust is max`() {
        val cmd = che_주민선정(createTestGeneral(rice = 1000), createTestEnv())
        cmd.city = createTestCity(trust = 100f)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `주민선정 should run and include trust change`() {
        val cmd = che_주민선정(createTestGeneral(leadership = 85, rice = 1000), createTestEnv(develCost = 110))
        cmd.city = createTestCity(trust = 80f)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("주민 선정"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"cityChanges\""))
        assertTrue(msg.contains("\"trust\":"))
        assertTrue(msg.contains("\"criticalResult\""))
    }

    @Test
    fun `기술연구 should pass constraints with enough resources`() {
        val cmd = che_기술연구(createTestGeneral(gold = 500, rice = 1000), createTestEnv(develCost = 100))
        cmd.city = createTestCity(trust = 80f)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Pass)
    }

    @Test
    fun `기술연구 should fail constraints when gold is insufficient`() {
        val cmd = che_기술연구(createTestGeneral(gold = 10, rice = 1000), createTestEnv(develCost = 100))
        cmd.city = createTestCity(trust = 80f)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `기술연구 should run and produce nation tech change`() {
        val cmd = che_기술연구(createTestGeneral(intel = 90, gold = 1000, rice = 1000), createTestEnv())
        cmd.city = createTestCity(trust = 90f)

        val result = runBlocking { cmd.run(LiteHashDRBG.build("tech_research_seed")) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("기술 연구"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"nationChanges\""))
        assertTrue(msg.contains("\"tech\":"))
        assertTrue(msg.contains("\"criticalResult\""))
    }

    @Test
    fun `숙련전환 should pass constraints with enough gold and rice`() {
        val arg = mapOf<String, Any>("srcArmType" to 1, "destArmType" to 2)
        val cmd = che_숙련전환(createTestGeneral(gold = 500, rice = 500), createTestEnv(), arg)
        cmd.city = createTestCity()

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Pass)
    }

    @Test
    fun `숙련전환 should fail constraints when rice is insufficient`() {
        val arg = mapOf<String, Any>("srcArmType" to 1, "destArmType" to 2)
        val cmd = che_숙련전환(createTestGeneral(gold = 500, rice = 10), createTestEnv(develCost = 100), arg)
        cmd.city = createTestCity()

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `숙련전환 should run and include dex conversion payload`() {
        val general = createTestGeneral(gold = 1000, rice = 1000).apply {
            meta["dex1"] = 100
        }
        val arg = mapOf<String, Any>("srcArmType" to 1, "destArmType" to 2)
        val cmd = che_숙련전환(general, createTestEnv(), arg)
        cmd.city = createTestCity()

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("숙련"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"dex1\":-40"))
        assertTrue(msg.contains("\"dex2\":36"))
        assertTrue(msg.contains("\"dexConversion\""))
    }

    @Test
    fun `숙련전환 should fail run when arg is missing`() {
        val cmd = che_숙련전환(createTestGeneral(gold = 1000, rice = 1000), createTestEnv(), null)
        cmd.city = createTestCity()

        val result = runBlocking { cmd.run(fixedRng) }

        assertFalse(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("인자가 없습니다"))
        assertNull(result.message)
    }

    @Test
    fun `물자조달 should pass constraints in supplied city`() {
        val cmd = che_물자조달(createTestGeneral(), createTestEnv())
        cmd.city = createTestCity(supplyState = 1)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Pass)
    }

    @Test
    fun `물자조달 should fail constraints in unsupplied city`() {
        val cmd = che_물자조달(createTestGeneral(), createTestEnv())
        cmd.city = createTestCity(supplyState = 0)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `물자조달 should run and include nation resource changes`() {
        val cmd = che_물자조달(createTestGeneral(leadership = 80, strength = 75, intel = 70), createTestEnv())
        cmd.city = createTestCity(frontState = 0)

        val result = runBlocking { cmd.run(LiteHashDRBG.build("supply_fetch_seed")) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("조달"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"nationChanges\""))
        assertTrue(msg.contains("\"criticalResult\""))
        assertTrue(msg.contains("\"gold\":") || msg.contains("\"rice\":"))
    }

    @Test
    fun `물자조달 front line should reduce gathered amount`() {
        val baseCmd = che_물자조달(createTestGeneral(leadership = 80, strength = 80, intel = 80), createTestEnv())
        baseCmd.city = createTestCity(frontState = 0)
        val frontCmd = che_물자조달(createTestGeneral(leadership = 80, strength = 80, intel = 80), createTestEnv())
        frontCmd.city = createTestCity(frontState = 1)

        val baseResult = runBlocking { baseCmd.run(LiteHashDRBG.build("supply_front_cmp")) }
        val frontResult = runBlocking { frontCmd.run(LiteHashDRBG.build("supply_front_cmp")) }
        val baseAmount = extractNationAmount(baseResult.message)
        val frontAmount = extractNationAmount(frontResult.message)

        assertTrue(baseResult.success)
        assertTrue(frontResult.success)
        assertTrue(frontAmount < baseAmount)
    }

    @Test
    fun `군량매매 should pass constraints when buying rice with gold`() {
        val arg = mapOf<String, Any>("buyRice" to true, "amount" to 500)
        val cmd = che_군량매매(createTestGeneral(gold = 1000, rice = 1000), createTestEnv(), arg)
        cmd.city = createTestCity(trade = 100)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Pass)
    }

    @Test
    fun `군량매매 should fail constraints when buying rice without gold`() {
        val arg = mapOf<String, Any>("buyRice" to true, "amount" to 500)
        val cmd = che_군량매매(createTestGeneral(gold = 0, rice = 1000), createTestEnv(), arg)
        cmd.city = createTestCity(trade = 100)

        val condition = cmd.checkFullCondition()
        assertTrue(condition is ConstraintResult.Fail)
    }

    @Test
    fun `군량매매 should run and include tax plus stat changes`() {
        val arg = mapOf<String, Any>("buyRice" to true, "amount" to 500)
        val cmd = che_군량매매(createTestGeneral(gold = 1000, rice = 1000, leadership = 70, strength = 60, intel = 80), createTestEnv(), arg)
        cmd.city = createTestCity(trade = 100)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertTrue(result.logs[0].contains("군량"))
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"statChanges\""))
        assertTrue(msg.contains("\"gold\":-"))
        assertTrue(msg.contains("\"rice\":"))
        assertTrue(msg.contains("\"nationTax\""))
    }

    @Test
    fun `군량매매 should round small amount up to 100 unit trade`() {
        val arg = mapOf<String, Any>("buyRice" to true, "amount" to 55)
        val cmd = che_군량매매(createTestGeneral(gold = 1000, rice = 1000), createTestEnv(), arg)
        cmd.city = createTestCity(trade = 100)

        val result = runBlocking { cmd.run(LiteHashDRBG.build("trade_rounding_seed")) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        val msg = result.message ?: ""
        assertTrue(msg.contains("\"rice\":100"))
        assertTrue(msg.contains("\"gold\":-103"))
    }

    @Test
    fun `createTestNation helper should build valid fixture`() {
        val nation = createTestNation(id = 7, level = 2, gold = 1234, rice = 4321)

        assertEquals(7, nation.id)
        assertEquals(2, nation.level.toInt())
        assertEquals(1234, nation.gold)
        assertEquals(4321, nation.rice)
    }

    private fun extractNationAmount(message: String?): Int {
        if (message == null) return 0
        val match = Regex("\\\"nationChanges\\\":\\{\\\"(?:gold|rice)\\\":(-?\\d+)").find(message)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
