package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.nation.Nation휴식
import com.opensam.command.nation.che_감축
import com.opensam.command.nation.che_국기변경
import com.opensam.command.nation.che_국호변경
import com.opensam.command.nation.che_몰수
import com.opensam.command.nation.che_물자원조
import com.opensam.command.nation.che_발령
import com.opensam.command.nation.che_백성동원
import com.opensam.command.nation.che_증축
import com.opensam.command.nation.che_천도
import com.opensam.engine.DiplomacyService
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.NationRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import java.time.OffsetDateTime
import kotlin.random.Random

class NationResourceCommandTest {

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
    fun `Nation휴식 always passes condition and runs`() {
        val cmd = Nation휴식(createGeneral(officerLevel = 1), env())

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.isEmpty())
    }

    @Test
    fun `몰수 fails for non-chief and runs with resource transfer`() {
        val nonChief = createGeneral(officerLevel = 5, nationId = 1)
        val failCmd = che_몰수(nonChief, env(), mapOf("isGold" to true, "amount" to 500))
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1)
        failCmd.destGeneral = createGeneral(id = 2, nationId = 1, gold = 1500)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val chief = createGeneral(officerLevel = 12, nationId = 1)
        val nation = createNation(id = 1, gold = 10000, rice = 10000)
        val target = createGeneral(id = 2, nationId = 1, gold = 1500, rice = 2000)
        val cmd = che_몰수(chief, env(), mapOf("isGold" to true, "amount" to 500))
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destGeneral = target

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(1000, target.gold)
        assertEquals(10500, nation.gold)
        assertEquals(1, target.betray.toInt())
        assertTrue(result.logs.any { it.contains("몰수") })
    }

    @Test
    fun `감축 fails for non-chief and runs with level down and capacity shrink`() {
        val nonChief = createGeneral(officerLevel = 5)
        val failCmd = che_감축(nonChief, env())
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val chief = createGeneral(officerLevel = 12)
        val nation = createNation(id = 1, gold = 10000, rice = 10000)
        val city = createCity(nationId = 1)
        city.level = 2
        city.popMax = 50000
        val cmd = che_감축(chief, env())
        cmd.city = city
        cmd.nation = nation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(1, city.level.toInt())
        assertEquals(40000, city.popMax)
        assertEquals(9500, nation.gold)
        assertEquals(9500, nation.rice)
    }

    @Test
    fun `증축 fails with low nation resource and runs with level up and max increase`() {
        val chief = createGeneral(officerLevel = 12)
        val failCmd = che_증축(chief, env())
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1, gold = 2000, rice = 3000)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val nation = createNation(id = 1, gold = 10000, rice = 10000)
        val city = createCity(nationId = 1)
        city.level = 2
        city.popMax = 40000
        val cmd = che_증축(chief, env())
        cmd.city = city
        cmd.nation = nation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(3, city.level.toInt())
        assertEquals(50000, city.popMax)
        assertEquals(8500, nation.gold)
        assertEquals(8500, nation.rice)
        assertTrue(result.logs.any { it.contains("증축") })
    }

    @Test
    fun `발령 fails without destination general and runs to move officer city`() {
        val chief = createGeneral(officerLevel = 12)
        val failCmd = che_발령(chief, env())
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1)
        failCmd.destCity = createCity(id = 2, nationId = 1)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val target = createGeneral(id = 2, nationId = 1, cityId = 1)
        target.troopId = 99
        val cmd = che_발령(chief, env())
        cmd.city = createCity(nationId = 1)
        cmd.nation = createNation(id = 1)
        cmd.destGeneral = target
        cmd.destCity = createCity(id = 2, nationId = 1)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(2L, target.cityId)
        assertEquals(0L, target.troopId)
        assertTrue(result.logs.any { it.contains("발령") })
    }

    @Test
    fun `천도 fails for non-chief and runs to change nation capital`() {
        val nonChief = createGeneral(officerLevel = 5)
        val failCmd = che_천도(nonChief, env())
        failCmd.city = createCity(nationId = 1)
        failCmd.destCity = createCity(id = 2, nationId = 1)
        failCmd.nation = createNation(id = 1)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val chief = createGeneral(officerLevel = 12)
        val nation = createNation(id = 1, gold = 10000, rice = 10000)
        nation.capitalCityId = 1
        val cmd = che_천도(chief, env())
        cmd.city = createCity(id = 1, nationId = 1)
        cmd.destCity = createCity(id = 2, nationId = 1)
        cmd.nation = nation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(2L, nation.capitalCityId)
        assertEquals(8000, nation.gold)
        assertEquals(8000, nation.rice)
        assertTrue(result.logs.any { it.contains("천도") })
    }

    @Test
    fun `백성동원 fails when strategic command is blocked and runs with NPC saves`() {
        val chief = createGeneral(officerLevel = 12)
        val failCmd = che_백성동원(chief, env())
        failCmd.city = createCity(nationId = 1)
        failCmd.destCity = createCity(id = 2, nationId = 1)
        failCmd.nation = createNation(id = 1, strategicCmdLimit = 3)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val generalRepository = mock(GeneralRepository::class.java)
        val mockServices = CommandServices(
            generalRepository = generalRepository,
            cityRepository = mock(CityRepository::class.java),
            nationRepository = mock(NationRepository::class.java),
            diplomacyService = mock(DiplomacyService::class.java),
        )

        val nation = createNation(id = 1, strategicCmdLimit = 0)
        val destCity = createCity(id = 2, nationId = 1)
        destCity.pop = 25000

        val cmd = che_백성동원(chief, env())
        cmd.city = createCity(nationId = 1)
        cmd.destCity = destCity
        cmd.nation = nation
        cmd.services = mockServices

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        assertEquals(5000, destCity.pop)
        verify(generalRepository, times(2)).save(org.mockito.Mockito.any(General::class.java))
        assertTrue(result.logs.any { it.contains("백성") })
    }

    @Test
    fun `물자원조 fails for same destination nation and runs with resource transfer`() {
        val chief = createGeneral(officerLevel = 12, nationId = 1)
        val failCmd = che_물자원조(chief, env(), mapOf("goldAmount" to 500, "riceAmount" to 600))
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1, gold = 10000, rice = 10000)
        failCmd.destNation = createNation(id = 1, gold = 1000, rice = 1000)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val nation = createNation(id = 1, gold = 10000, rice = 10000)
        val allyNation = createNation(id = 2, gold = 1000, rice = 2000)
        val cmd = che_물자원조(chief, env(), mapOf("goldAmount" to 500, "riceAmount" to 600))
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destNation = allyNation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9500, nation.gold)
        assertEquals(9400, nation.rice)
        assertEquals(1500, allyNation.gold)
        assertEquals(2600, allyNation.rice)
        assertTrue(result.logs.any { it.contains("지원") })
    }

    @Test
    fun `국기변경 fails for non-chief and runs to mutate nation color`() {
        val failCmd = che_국기변경(createGeneral(officerLevel = 5), env(), mapOf("colorType" to "blue"))
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val nation = createNation(id = 1)
        val cmd = che_국기변경(createGeneral(officerLevel = 12), env(), mapOf("colorType" to "blue"))
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals("blue", nation.color)
        assertTrue(result.logs.any { it.contains("국기") })
    }

    @Test
    fun `국호변경 fails for non-chief and runs to mutate nation name`() {
        val failCmd = che_국호변경(createGeneral(officerLevel = 5), env(), mapOf("nationName" to "신국호"))
        failCmd.city = createCity(nationId = 1)
        failCmd.nation = createNation(id = 1)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)

        val nation = createNation(id = 1)
        val cmd = che_국호변경(createGeneral(officerLevel = 12), env(), mapOf("nationName" to "신국호"))
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)
        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals("신국호", nation.name)
        assertTrue(result.logs.any { it.contains("국호") })
    }
}
