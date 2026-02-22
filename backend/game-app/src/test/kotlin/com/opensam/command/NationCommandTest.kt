package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.nation.che_선전포고
import com.opensam.command.nation.che_포상
import com.opensam.command.nation.che_초토화
import com.opensam.command.nation.che_필사즉생
import com.opensam.command.nation.event_극병연구
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class NationCommandTest {

    private val fixedRng = Random(42)

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        cityId: Long = 1,
        officerLevel: Short = 12,
        gold: Int = 1000,
        rice: Int = 1000,
    ): General = General(
        id = id,
        worldId = 1,
        name = "테스트장수$id",
        nationId = nationId,
        cityId = cityId,
        officerLevel = officerLevel,
        gold = gold,
        rice = rice,
        leadership = 50,
        strength = 50,
        intel = 50,
        politics = 50,
        charm = 50,
        turnTime = OffsetDateTime.now(),
    )

    private fun createCity(
        id: Long = 1,
        nationId: Long = 1,
        supplyState: Short = 1,
    ): City = City(
        id = id,
        worldId = 1,
        name = "테스트도시$id",
        nationId = nationId,
        supplyState = supplyState,
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
        trust = 80,
    )

    private fun createNation(
        id: Long = 1,
        gold: Int = 200000,
        rice: Int = 200000,
        strategicCmdLimit: Short = 0,
    ): Nation = Nation(
        id = id,
        worldId = 1,
        name = "테스트국가$id",
        color = "#FF0000",
        gold = gold,
        rice = rice,
        level = 7,
        strategicCmdLimit = strategicCmdLimit,
        chiefGeneralId = 1,
    )

    private fun env(
        year: Int = 200,
        month: Int = 1,
        startYear: Int = 190,
    ): CommandEnv = CommandEnv(
        year = year,
        month = month,
        startYear = startYear,
        worldId = 1,
        realtimeMode = false,
    )

    @Test
    fun `선전포고 requires chief and destination nation`() {
        val nonChief = createGeneral(officerLevel = 5)
        val cmd1 = che_선전포고(nonChief, env())
        cmd1.city = createCity()
        cmd1.nation = createNation()
        cmd1.destNation = createNation(id = 2)
        val r1 = cmd1.checkFullCondition()
        assertTrue(r1 is ConstraintResult.Fail)
        assertTrue((r1 as ConstraintResult.Fail).reason.contains("군주"))

        val chief = createGeneral(officerLevel = 12)
        val cmd2 = che_선전포고(chief, env())
        cmd2.city = createCity()
        cmd2.nation = createNation()
        val r2 = cmd2.checkFullCondition()
        assertTrue(r2 is ConstraintResult.Fail)
        assertTrue((r2 as ConstraintResult.Fail).reason.contains("오프닝"))
    }

    @Test
    fun `선전포고 fails with opening constraint when conditions otherwise valid`() {
        val general = createGeneral(officerLevel = 12)
        val cmd = che_선전포고(general, env(year = 192, startYear = 191))
        cmd.city = createCity()
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)

        // BeOpeningPart(env.startYear + 1) 제약 조건 검증
        // NOTE: BeOpeningPart 파라미터 로직 수정 필요 (env.startYear+1 → 상대년도)
        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Fail)
        assertTrue((check as ConstraintResult.Fail).reason.contains("오프닝"))
    }

    @Test
    fun `포상 requires friendly destination general`() {
        val chief = createGeneral(officerLevel = 12, nationId = 1)
        val cmd = che_포상(chief, env(), mapOf("isGold" to true, "amount" to 1000))
        cmd.city = createCity(nationId = 1)
        cmd.nation = createNation(id = 1)

        val withoutDest = cmd.checkFullCondition()
        assertTrue(withoutDest is ConstraintResult.Fail)
        assertTrue((withoutDest as ConstraintResult.Fail).reason.contains("대상 장수"))

        cmd.destGeneral = createGeneral(id = 2, nationId = 2)
        val enemyDest = cmd.checkFullCondition()
        assertTrue(enemyDest is ConstraintResult.Fail)
        assertTrue((enemyDest as ConstraintResult.Fail).reason.contains("아군"))
    }

    @Test
    fun `포상 runs and writes reward log`() {
        val chief = createGeneral(officerLevel = 12, nationId = 1)
        val cmd = che_포상(chief, env(), mapOf("isGold" to false, "amount" to 1234))
        cmd.city = createCity(nationId = 1)
        cmd.nation = createNation(id = 1)
        cmd.destGeneral = createGeneral(id = 2, nationId = 1)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("수여") })
        assertTrue(result.logs.any { it.contains("쌀") })
    }

    @Test
    fun `초토화 has expected pre and post turn requirement`() {
        val cmd = che_초토화(createGeneral(), env())
        assertEquals(2, cmd.getPreReqTurn())
        assertEquals(24, cmd.getPostReqTurn())
    }

    @Test
    fun `초토화 requires occupied destination city and chief`() {
        val nonChief = createGeneral(officerLevel = 5, nationId = 1)
        val cmd1 = che_초토화(nonChief, env())
        cmd1.city = createCity(nationId = 1)
        cmd1.destCity = createCity(id = 2, nationId = 1)
        val r1 = cmd1.checkFullCondition()
        assertTrue(r1 is ConstraintResult.Fail)
        assertTrue((r1 as ConstraintResult.Fail).reason.contains("군주"))

        val chief = createGeneral(officerLevel = 12, nationId = 1)
        val cmd2 = che_초토화(chief, env())
        cmd2.city = createCity(nationId = 1)
        cmd2.destCity = createCity(id = 2, nationId = 2)
        val r2 = cmd2.checkFullCondition()
        assertTrue(r2 is ConstraintResult.Fail)
        assertTrue((r2 as ConstraintResult.Fail).reason.contains("아군 도시"))
    }

    @Test
    fun `초토화 runs and emits action log`() {
        val chief = createGeneral(officerLevel = 12, nationId = 1)
        val cmd = che_초토화(chief, env())
        cmd.city = createCity(nationId = 1)
        cmd.destCity = createCity(id = 2, nationId = 1)
        cmd.nation = createNation(id = 1)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("초토화") })
    }

    @Test
    fun `극병연구 requires nation resource threshold and has expected cost`() {
        val chief = createGeneral(officerLevel = 12)

        val lowGoldNation = createNation(gold = 100000, rice = 200000)
        val cmd1 = event_극병연구(chief, env())
        cmd1.city = createCity()
        cmd1.nation = lowGoldNation
        val r1 = cmd1.checkFullCondition()
        assertTrue(r1 is ConstraintResult.Fail)
        assertTrue((r1 as ConstraintResult.Fail).reason.contains("국고"))

        val lowRiceNation = createNation(gold = 200000, rice = 100000)
        val cmd2 = event_극병연구(chief, env())
        cmd2.city = createCity()
        cmd2.nation = lowRiceNation
        val r2 = cmd2.checkFullCondition()
        assertTrue(r2 is ConstraintResult.Fail)
        assertTrue((r2 as ConstraintResult.Fail).reason.contains("병량"))

        val cmd3 = event_극병연구(chief, env())
        assertEquals(23, cmd3.getPreReqTurn())
        assertEquals(100000, cmd3.getCost().gold)
        assertEquals(100000, cmd3.getCost().rice)
    }

    @Test
    fun `극병연구 runs and emits completion log`() {
        val chief = createGeneral(officerLevel = 12)
        val cmd = event_극병연구(chief, env())
        cmd.city = createCity()
        cmd.nation = createNation(gold = 200000, rice = 200000)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains("극병 연구") })
    }

    @Test
    fun `nation strategic command fails when strategic limit is active`() {
        val chief = createGeneral(officerLevel = 12)
        val cmd = che_필사즉생(chief, env())
        cmd.city = createCity()
        cmd.nation = createNation(strategicCmdLimit = 5)

        val result = cmd.checkFullCondition()
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("전략 명령 대기"))
    }
}
