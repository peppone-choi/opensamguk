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

class GeneralPoliticalCommandTest {

    private fun createTestGeneral(
        id: Long = 1,
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
        age: Short = 20,
        npcState: Short = 0,
        makeLimit: Short = 0,
        specialCode: String = "None",
        special2Code: String = "None",
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "테스트장수$id",
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
            age = age,
            npcState = npcState,
            makeLimit = makeLimit,
            specialCode = specialCode,
            special2Code = special2Code,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createTestCity(
        id: Long = 1,
        nationId: Long = 1,
        supplyState: Short = 1,
        name: String = "테스트도시",
    ): City {
        return City(
            id = id,
            worldId = 1,
            name = name,
            nationId = nationId,
            agri = 500,
            agriMax = 1000,
            comm = 500,
            commMax = 1000,
            secu = 500,
            secuMax = 1000,
            def = 500,
            defMax = 1000,
            wall = 500,
            wallMax = 1000,
            pop = 10000,
            popMax = 50000,
            trust = 80f,
            supplyState = supplyState,
            frontState = 0,
        )
    }

    private fun createTestNation(
        id: Long = 1,
        level: Short = 1,
        gold: Int = 10000,
        rice: Int = 10000,
        capitalCityId: Long? = 1,
    ): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "테스트국가$id",
            color = "#FF0000",
            gold = gold,
            rice = rice,
            level = level,
            capitalCityId = capitalCityId,
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

    @Test
    fun `등용 should pass constraints and run`() {
        val general = createTestGeneral(gold = 1000, nationId = 1, cityId = 1)
        val env = createTestEnv()
        val cmd = 등용(general, env, mapOf("destGeneralID" to 2L))
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destGeneral = createTestGeneral(id = 2, nationId = 2, experience = 1000, dedication = 1000)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("등용"))
    }

    @Test
    fun `등용수락 should pass constraints and run`() {
        val general = createTestGeneral(nationId = 0, troopId = 0, gold = 1500, rice = 1400)
        val env = createTestEnv()
        val cmd = 등용수락(general, env)
        cmd.destNation = createTestNation(id = 2, capitalCityId = 3)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"nation\":2"))
    }

    @Test
    fun `임관 should pass constraints and run`() {
        val general = createTestGeneral(nationId = 0, makeLimit = 0)
        val env = createTestEnv()
        val cmd = 임관(general, env)
        cmd.destNation = createTestNation(id = 3)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs[0].contains("임관"))
    }

    @Test
    fun `랜덤임관 should pass constraints and run`() {
        val general = createTestGeneral(nationId = 0, makeLimit = 0)
        val env = createTestEnv()
        val cmd = 랜덤임관(general, env)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("randomJoin"))
    }

    @Test
    fun `장수대상임관 should pass constraints and run`() {
        val general = createTestGeneral(nationId = 0, makeLimit = 0)
        val env = createTestEnv()
        val cmd = 장수대상임관(general, env)
        cmd.destNation = createTestNation(id = 4, capitalCityId = 9)
        cmd.destGeneral = createTestGeneral(id = 2, cityId = 7, nationId = 4)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"city\":7"))
    }

    @Test
    fun `은퇴 should pass constraints and run`() {
        val general = createTestGeneral(age = 60)
        val env = createTestEnv()
        val cmd = 은퇴(general, env)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("rebirth"))
    }

    @Test
    fun `무작위건국 should fail opening constraint and still run`() {
        val general = createTestGeneral(officerLevel = 12, makeLimit = 0)
        val env = createTestEnv(year = 190, month = 2, startYear = 190)
        val cmd = 무작위건국(general, env, mapOf("nationName" to "신국", "nationType" to "군벌", "colorType" to 1))
        cmd.nation = createTestNation(level = 0)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Fail)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("foundNation"))
    }

    @Test
    fun `모반시도 should fail constraints and still run execution`() {
        val general = createTestGeneral(nationId = 1, officerLevel = 12)
        val env = createTestEnv()
        val cmd = 모반시도(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.nation = createTestNation(id = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("군주"))

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("rebellionResult"))
    }

    @Test
    fun `선양 should fail for non-lord`() {
        val general = createTestGeneral(nationId = 1, officerLevel = 11)
        val env = createTestEnv()
        val cmd = 선양(general, env)
        cmd.destGeneral = createTestGeneral(id = 2, nationId = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
    }

    @Test
    fun `선양 should pass for lord and run`() {
        val general = createTestGeneral(nationId = 1, officerLevel = 12)
        val env = createTestEnv()
        val cmd = 선양(general, env)
        cmd.nation = createTestNation(id = 1)
        cmd.destGeneral = createTestGeneral(id = 2, nationId = 1)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("destGeneralChanges"))
    }

    @Test
    fun `해산 should pass lord constraint and run`() {
        val general = createTestGeneral(officerLevel = 12)
        val env = createTestEnv(year = 190, month = 2, startYear = 190)
        val cmd = 해산(general, env)
        cmd.nation = createTestNation(level = 0)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("disbandNation"))
    }

    @Test
    fun `인재탐색 should pass constraints and run`() {
        val general = createTestGeneral(gold = 500)
        val env = createTestEnv()
        val cmd = 인재탐색(general, env)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.isNotEmpty())
    }

    @Test
    fun `증여 should pass constraints and run`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 2000, rice = 2000)
        val env = createTestEnv()
        val cmd = 증여(general, env, mapOf("isGold" to true, "amount" to 500))
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destGeneral = createTestGeneral(id = 2, nationId = 1, cityId = 1)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("destGeneralChanges"))
    }

    @Test
    fun `장비매매 should pass constraints and run failure without args`() {
        val general = createTestGeneral(gold = 5000)
        val env = createTestEnv()
        val cmd = 장비매매(general, env)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertFalse(result.success)
        assertTrue(result.logs[0].contains("인자가 없습니다"))
    }

    @Test
    fun `내정특기초기화 should pass constraints and run`() {
        val general = createTestGeneral(specialCode = "che_내정_테스트")
        val env = createTestEnv()
        val cmd = 내정특기초기화(general, env)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"specialCode\":\"None\""))
    }

    @Test
    fun `전투특기초기화 should pass constraints and run`() {
        val general = createTestGeneral(special2Code = "che_전투_테스트")
        val env = createTestEnv()
        val cmd = 전투특기초기화(general, env)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"special2Code\":\"None\""))
    }

    @Test
    fun `NPC능동 should pass npc constraint and run`() {
        val general = createTestGeneral(npcState = 1)
        val env = createTestEnv()
        val cmd = NPC능동(general, env, mapOf("optionText" to "순간이동", "destCityID" to 7))

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("npcAction"))
    }

    @Test
    fun `CR건국 should fail opening constraint and still run`() {
        val general = createTestGeneral(officerLevel = 12, nationId = 1, cityId = 1, makeLimit = 0)
        val env = createTestEnv(year = 190, month = 2, startYear = 190)
        val cmd = CR건국(general, env, mapOf("nationName" to "신한", "nationType" to "군벌", "colorType" to 2))
        cmd.nation = createTestNation(level = 0)
        cmd.city = createTestCity(id = 1, nationId = 0)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Fail)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("nationFoundation"))
    }

    @Test
    fun `CR맹훈련 should pass constraints and run`() {
        val general = createTestGeneral(nationId = 1, crew = 1000, train = 50, leadership = 80, crewType = 1)
        val env = createTestEnv()
        val cmd = CR맹훈련(general, env)
        cmd.city = createTestCity(nationId = 1)

        assertTrue(cmd.checkFullCondition() is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"rice\":-500"))
    }
}
