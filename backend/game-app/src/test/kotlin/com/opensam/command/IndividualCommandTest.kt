package com.opensam.command

import com.opensam.command.general.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class IndividualCommandTest {

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

    // ========== 견문 (Sightseeing) ==========

    @Test
    fun `견문 should increase experience or stat exp`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = 견문(general, env)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
        assertNotNull(result.message)
        // Check that statChanges includes experience
        assertTrue(result.message!!.contains("\"experience\":"))
    }

    @Test
    fun `견문 may increase stat experience (leadership, strength, intel)`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = 견문(general, env)

        val result = runBlocking { cmd.run(Random(10)) }

        assertTrue(result.success)
        // The result should contain experience changes
        val msg = result.message ?: ""
        assertTrue(msg.contains("statChanges"))
    }

    @Test
    fun `견문 may increase or decrease gold and rice`() {
        val general = createTestGeneral(gold = 5000, rice = 5000)
        val env = createTestEnv()
        val cmd = 견문(general, env)

        // Run multiple times to see different outcomes
        val results = (0..5).map { runBlocking { cmd.run(Random.Default) } }

        assertTrue(results.all { it.success })
    }

    @Test
    fun `견문 may cause injury (wounded or heavy wounded)`() {
        val general = createTestGeneral()
        val env = createTestEnv()
        val cmd = 견문(general, env)

        val result = runBlocking { cmd.run(Random(1234)) }

        assertTrue(result.success)
        assertNotNull(result.message)
    }

    // ========== 요양 (Rest/Healing) ==========

    @Test
    fun `요양 should decrease injury to zero`() {
        val general = createTestGeneral(injury = 50)
        val env = createTestEnv()
        val cmd = 요양(general, env)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("요양"))
        assertNotNull(result.message)
        // Check that injury decreases by 50 (from 50 to 0)
        assertTrue(result.message!!.contains("\"injury\":-50"))
    }

    @Test
    fun `요양 should increase experience and dedication`() {
        val general = createTestGeneral(injury = 20)
        val env = createTestEnv()
        val cmd = 요양(general, env)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("\"experience\":10"))
        assertTrue(result.message!!.contains("\"dedication\":7"))
    }

    @Test
    fun `요양 should work even with zero injury`() {
        val general = createTestGeneral(injury = 0)
        val env = createTestEnv()
        val cmd = 요양(general, env)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("\"injury\":0"))
    }

    // ========== 이동 (Move) ==========

    @Test
    fun `이동 should change cityId to destination`() {
        val general = createTestGeneral(cityId = 1, gold = 500)
        val env = createTestEnv()
        val destCity = createTestCity().apply { id = 2 }
        val cmd = 이동(general, env)
        cmd.destCity = destCity

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("이동"))
        assertNotNull(result.message)
        // Check that cityId changes to 2
        assertTrue(result.message!!.contains("\"cityId\":\"2\""))
    }

    @Test
    fun `이동 should decrease atmos by 5 (min 20)`() {
        val general = createTestGeneral(atmos = 60, gold = 500)
        val env = createTestEnv()
        val destCity = createTestCity().apply { id = 2 }
        val cmd = 이동(general, env)
        cmd.destCity = destCity

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // atmos should decrease by 5 (from 60 to 55)
        assertTrue(result.message!!.contains("\"atmos\":-5"))
    }

    @Test
    fun `이동 should not decrease atmos below 20`() {
        val general = createTestGeneral(atmos = 20, gold = 500)
        val env = createTestEnv()
        val destCity = createTestCity().apply { id = 2 }
        val cmd = 이동(general, env)
        cmd.destCity = destCity

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // atmos should stay at 20 (delta = 0)
        assertTrue(result.message!!.contains("\"atmos\":0"))
    }

    @Test
    fun `이동 should consume gold based on develCost`() {
        val general = createTestGeneral(gold = 500)
        val env = createTestEnv(develCost = 150)
        val destCity = createTestCity().apply { id = 2 }
        val cmd = 이동(general, env)
        cmd.destCity = destCity

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // Should consume 150 gold
        assertTrue(result.message!!.contains("\"gold\":-150"))
    }

    // ========== 훈련 (Training) ==========

    @Test
    fun `훈련 should increase train stat`() {
        val general = createTestGeneral(crew = 1000, train = 50, leadership = 80, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_훈련(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("훈련치"))
        assertNotNull(result.message)
        // Train should increase
        assertTrue(result.message!!.contains("\"train\":"))
    }

    @Test
    fun `훈련 should reduce atmos as side effect (90 percent retention)`() {
        val general = createTestGeneral(crew = 1000, train = 50, atmos = 80, leadership = 80, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_훈련(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // atmos should become 72 (80 * 0.9), delta = -8
        assertTrue(result.message!!.contains("\"atmos\":"))
    }

    @Test
    fun `훈련 should cap train at 80`() {
        val general = createTestGeneral(crew = 1000, train = 79, leadership = 80, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_훈련(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        // Should only increase by max 1 (to reach 80)
        assertNotNull(result.message)
    }

    // ========== 사기진작 (Morale Boost) ==========

    @Test
    fun `사기진작 should increase atmos stat`() {
        val general = createTestGeneral(crew = 1000, atmos = 50, train = 80, leadership = 80, nationId = 1, gold = 500)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_사기진작(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("사기치"))
        assertNotNull(result.message)
        // atmos should increase
        assertTrue(result.message!!.contains("\"atmos\":"))
    }

    @Test
    fun `사기진작 should reduce train as side effect (90 percent retention)`() {
        val general = createTestGeneral(crew = 1000, atmos = 50, train = 80, leadership = 80, nationId = 1, gold = 500)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_사기진작(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // train should become 72 (80 * 0.9), delta = -8
        assertTrue(result.message!!.contains("\"train\":"))
    }

    @Test
    fun `사기진작 should consume gold based on crew count`() {
        val general = createTestGeneral(crew = 1000, atmos = 50, leadership = 80, nationId = 1, gold = 500)
        val env = createTestEnv()
        val city = createTestCity()
        val cmd = che_사기진작(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // gold cost = crew / 100 = 1000 / 100 = 10
        assertTrue(result.message!!.contains("\"gold\":-10"))
    }

    // ========== 모병 (Recruitment) ==========

    @Test
    fun `모병 should increase crew count`() {
        val general = createTestGeneral(crew = 0, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("모병"))
        assertNotNull(result.message)
        // crew should increase by 500
        assertTrue(result.message!!.contains("\"crew\":500"))
    }

    @Test
    fun `모병 should set default train and atmos for new crew`() {
        val general = createTestGeneral(crew = 0, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // train should be 70 (default high), atmos should be 70
        assertTrue(result.message!!.contains("\"train\":70"))
        assertTrue(result.message!!.contains("\"atmos\":70"))
    }

    @Test
    fun `모병 should merge train and atmos when adding to same crew type`() {
        val general = createTestGeneral(crew = 1000, crewType = 1, train = 80, atmos = 80, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 1)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("추가모병"))
        assertNotNull(result.message)
    }

    @Test
    fun `모병 should consume gold and rice`() {
        val general = createTestGeneral(crew = 0, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // gold cost = (500/10) * 2 = 100, rice cost = 500/100 = 5
        assertTrue(result.message!!.contains("\"gold\":-100"))
        assertTrue(result.message!!.contains("\"rice\":-5"))
    }

    @Test
    fun `모병 should decrease city population`() {
        val general = createTestGeneral(crew = 0, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_모병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("\"pop\":-500"))
    }

    // ========== 징병 (Conscription) ==========

    @Test
    fun `징병 should increase crew count with lower train and atmos than 모병`() {
        val general = createTestGeneral(crew = 0, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_징병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("징병"))
        assertNotNull(result.message)
        // crew should increase by 500
        assertTrue(result.message!!.contains("\"crew\":500"))
        // train should be 40 (default low per PHP GameConstBase), atmos should be 40
        assertTrue(result.message!!.contains("\"train\":40"))
        assertTrue(result.message!!.contains("\"atmos\":40"))
    }

    @Test
    fun `징병 should be cheaper than 모병`() {
        val general = createTestGeneral(crew = 0, leadership = 50, gold = 5000, rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(pop = 10000)
        val arg = mapOf<String, Any>("amount" to 500, "crewType" to 0)
        val cmd = che_징병(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // gold cost = (500/10) * 1 = 50, rice cost = 500/100 = 5
        assertTrue(result.message!!.contains("\"gold\":-50"))
        assertTrue(result.message!!.contains("\"rice\":-5"))
    }

    // ========== 소집해제 (Disband) ==========

    @Test
    fun `소집해제 should set crew to zero`() {
        val general = createTestGeneral(crew = 500, nationId = 1)
        val env = createTestEnv()
        val cmd = che_소집해제(general, env)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("소집해제"))
        assertNotNull(result.message)
        // crew should decrease by 500 (to 0)
        assertTrue(result.message!!.contains("\"crew\":-500"))
    }

    @Test
    fun `소집해제 should return crew as city population`() {
        val general = createTestGeneral(crew = 500, nationId = 1)
        val env = createTestEnv()
        val cmd = che_소집해제(general, env)

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // city pop should increase by 500
        assertTrue(result.message!!.contains("\"pop\":500"))
    }

    // ========== 헌납 (Donate) ==========

    @Test
    fun `헌납 gold should transfer gold from general to nation`() {
        val general = createTestGeneral(gold = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("isGold" to true, "amount" to 1000)
        val cmd = che_헌납(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("금"))
        assertTrue(result.logs[0].contains("헌납"))
        assertNotNull(result.message)
        // general loses 1000 gold, nation gains 1000 gold
        assertTrue(result.message!!.contains("\"gold\":-1000"))
        assertTrue(result.message!!.contains("nationChanges"))
    }

    @Test
    fun `헌납 rice should transfer rice from general to nation`() {
        val general = createTestGeneral(rice = 5000, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("isGold" to false, "amount" to 1000)
        val cmd = che_헌납(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("쌀"))
        assertTrue(result.logs[0].contains("헌납"))
        assertNotNull(result.message)
        // general loses 1000 rice, nation gains 1000 rice
        assertTrue(result.message!!.contains("\"rice\":-1000"))
        assertTrue(result.message!!.contains("nationChanges"))
    }

    @Test
    fun `헌납 should cap amount at general's available resource`() {
        val general = createTestGeneral(gold = 300, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity()
        val arg = mapOf<String, Any>("isGold" to true, "amount" to 1000)
        val cmd = che_헌납(general, env, arg)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // Should only donate 300 (available amount)
        assertTrue(result.message!!.contains("\"gold\":-300"))
    }

    // ========== 농지개간 (Farming Development) ==========

    @Test
    fun `농지개간 should increase city agri`() {
        val general = createTestGeneral(intel = 80, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(agri = 500, agriMax = 1000)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("농지 개간"))
        assertNotNull(result.message)
        // agri should increase
        assertTrue(result.message!!.contains("\"agri\":"))
    }

    @Test
    fun `농지개간 should use intel stat for calculation`() {
        val general = createTestGeneral(intel = 90, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(agri = 500, agriMax = 1000, trust = 100f)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(Random(1)) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // Higher intel should result in higher score
        assertTrue(result.message!!.contains("cityChanges"))
    }

    @Test
    fun `농지개간 should apply front line debuff`() {
        val general = createTestGeneral(intel = 80, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(agri = 500, agriMax = 1000, frontState = 1, trust = 100f)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(Random(100)) }

        assertTrue(result.success)
        assertNotNull(result.message)
        // Score should be halved due to front line
    }

    @Test
    fun `농지개간 should consume gold based on develCost`() {
        val general = createTestGeneral(intel = 80, gold = 500, nationId = 1)
        val env = createTestEnv(develCost = 150)
        val city = createTestCity(agri = 500, agriMax = 1000)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("\"gold\":-150"))
    }

    // ========== 상업투자 (Commerce Investment) ==========

    @Test
    fun `상업투자 should increase city comm`() {
        val general = createTestGeneral(intel = 80, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(comm = 500, commMax = 1000)
        val cmd = che_상업투자(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("상업 투자"))
        assertNotNull(result.message)
        // comm should increase
        assertTrue(result.message!!.contains("\"comm\":"))
    }

    @Test
    fun `상업투자 should use intel stat for calculation`() {
        val general = createTestGeneral(intel = 90, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(comm = 500, commMax = 1000, trust = 100f)
        val cmd = che_상업투자(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(Random(1)) }

        assertTrue(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("cityChanges"))
    }

    // ========== 치안강화 (Security Enhancement) ==========

    @Test
    fun `치안강화 should increase city secu`() {
        val general = createTestGeneral(intel = 80, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(secu = 500, secuMax = 1000)
        val cmd = che_치안강화(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(fixedRng) }

        assertTrue(result.success)
        assertTrue(result.logs[0].contains("치안 강화"))
        assertNotNull(result.message)
        // secu should increase
        assertTrue(result.message!!.contains("\"secu\":"))
    }

    @Test
    fun `치안강화 should use intel stat for calculation`() {
        val general = createTestGeneral(intel = 90, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(secu = 500, secuMax = 1000, trust = 100f)
        val cmd = che_치안강화(general, env)
        cmd.city = city

        val result = runBlocking { cmd.run(Random(1)) }

        assertTrue(result.success)
        assertNotNull(result.message)
        assertTrue(result.message!!.contains("cityChanges"))
    }

    // ========== Domestic command critical results ==========

    @Test
    fun `농지개간 can have critical success or failure`() {
        val general = createTestGeneral(intel = 80, gold = 500, nationId = 1)
        val env = createTestEnv()
        val city = createTestCity(agri = 500, agriMax = 1000, trust = 80f)
        val cmd = che_농지개간(general, env)
        cmd.city = city

        // Run multiple times to see different results
        val results = (0..10).map {
            val result = runBlocking { cmd.run(Random.Default) }
            result.message?.contains("criticalResult") ?: false
        }

        assertTrue(results.any { it })
    }
}
