package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.nation.che_무작위수도이전
import com.opensam.command.nation.che_부대탈퇴지시
import com.opensam.command.nation.cr_인구이동
import com.opensam.command.nation.event_대검병연구
import com.opensam.command.nation.event_무희연구
import com.opensam.command.nation.event_산저병연구
import com.opensam.command.nation.event_상병연구
import com.opensam.command.nation.event_원융노병연구
import com.opensam.command.nation.event_음귀병연구
import com.opensam.command.nation.event_화륜차연구
import com.opensam.command.nation.event_화시병연구
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
import org.mockito.Mockito.`when`
import java.time.OffsetDateTime
import java.util.Optional
import kotlin.random.Random

class NationResearchSpecialCommandTest {

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

    private fun assertResearchCommand(
        actionLabel: String,
        expectedCost: Int,
        expectedPreReqTurn: Int,
        nationMetaKey: String,
        commandFactory: (General, CommandEnv) -> NationCommand,
    ) {
        val nonChief = createGeneral(officerLevel = 5)
        val nonChiefCmd = commandFactory(nonChief, env())
        nonChiefCmd.city = createCity()
        nonChiefCmd.nation = createNation(gold = 300000, rice = 300000)
        val nonChiefResult = nonChiefCmd.checkFullCondition()
        assertTrue(nonChiefResult is ConstraintResult.Fail)
        assertTrue((nonChiefResult as ConstraintResult.Fail).reason.contains("군주"))

        val chief = createGeneral(officerLevel = 12)

        val lowGoldCmd = commandFactory(chief, env())
        lowGoldCmd.city = createCity()
        lowGoldCmd.nation = createNation(gold = 50000, rice = 300000)
        val lowGoldResult = lowGoldCmd.checkFullCondition()
        assertTrue(lowGoldResult is ConstraintResult.Fail)
        assertTrue((lowGoldResult as ConstraintResult.Fail).reason.contains("국고"))

        val lowRiceCmd = commandFactory(chief, env())
        lowRiceCmd.city = createCity()
        lowRiceCmd.nation = createNation(gold = 300000, rice = 50000)
        val lowRiceResult = lowRiceCmd.checkFullCondition()
        assertTrue(lowRiceResult is ConstraintResult.Fail)
        assertTrue((lowRiceResult as ConstraintResult.Fail).reason.contains("병량"))

        val successNation = createNation(gold = 300000, rice = 300000)
        val successCmd = commandFactory(chief, env())
        successCmd.city = createCity()
        successCmd.nation = successNation

        val successCondition = successCmd.checkFullCondition()
        assertTrue(successCondition is ConstraintResult.Pass)
        assertEquals(expectedPreReqTurn, successCmd.getPreReqTurn())
        assertEquals(expectedCost, successCmd.getCost().gold)
        assertEquals(expectedCost, successCmd.getCost().rice)

        val beforeGold = successNation.gold
        val beforeRice = successNation.rice
        val result = runBlocking { successCmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.logs.any { it.contains(actionLabel) })
        assertEquals(beforeGold - expectedCost, successNation.gold)
        assertEquals(beforeRice - expectedCost, successNation.rice)
        assertEquals(1, successNation.meta[nationMetaKey])
        assertEquals(100, chief.experience)
        assertEquals(100, chief.dedication)
    }

    @Test
    fun `대검병 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "대검병 연구",
            expectedCost = 50000,
            expectedPreReqTurn = 11,
            nationMetaKey = "can_대검병사용",
            commandFactory = ::event_대검병연구,
        )
    }

    @Test
    fun `무희 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "무희 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_무희사용",
            commandFactory = ::event_무희연구,
        )
    }

    @Test
    fun `산저병 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "산저병 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_산저병사용",
            commandFactory = ::event_산저병연구,
        )
    }

    @Test
    fun `상병 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "상병 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_상병사용",
            commandFactory = ::event_상병연구,
        )
    }

    @Test
    fun `원융노병 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "원융노병 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_원융노병사용",
            commandFactory = ::event_원융노병연구,
        )
    }

    @Test
    fun `음귀병 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "음귀병 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_음귀병사용",
            commandFactory = ::event_음귀병연구,
        )
    }

    @Test
    fun `화륜차 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "화륜차 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_화륜차사용",
            commandFactory = ::event_화륜차연구,
        )
    }

    @Test
    fun `화시병 연구 command validates constraints timing cost and run`() {
        assertResearchCommand(
            actionLabel = "화시병 연구",
            expectedCost = 100000,
            expectedPreReqTurn = 23,
            nationMetaKey = "can_화시병사용",
            commandFactory = ::event_화시병연구,
        )
    }

    @Test
    fun `무작위수도이전 checks constraints cost and successful run with services`() {
        val failCmd = che_무작위수도이전(createGeneral(officerLevel = 5), env(year = 189, startYear = 190))
        failCmd.city = createCity(nationId = 1)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)
        assertTrue((fail as ConstraintResult.Fail).reason.contains("군주"))

        val cityRepository = mock(CityRepository::class.java)
        val services = CommandServices(
            generalRepository = mock(GeneralRepository::class.java),
            cityRepository = cityRepository,
            nationRepository = mock(NationRepository::class.java),
            diplomacyService = mock(DiplomacyService::class.java),
        )

        val commandEnv = env(year = 189, startYear = 190)
        commandEnv.gameStor["neutralCities"] = listOf(2)
        val nation = createNation(id = 1)
        nation.capitalCityId = 1
        val targetCity = createCity(id = 2, nationId = 0)

        `when`(cityRepository.findById(2L)).thenReturn(Optional.of(targetCity))

        val cmd = che_무작위수도이전(createGeneral(officerLevel = 12), commandEnv)
        cmd.city = createCity(id = 1, nationId = 1)
        cmd.nation = nation
        cmd.services = services

        assertEquals(0, cmd.getCost().gold)
        assertEquals(0, cmd.getCost().rice)
        assertEquals(1, cmd.getPreReqTurn())

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(1L, targetCity.nationId)
        assertEquals(2L, nation.capitalCityId)
        assertTrue(result.logs.any { it.contains("국가를 옮겼습니다") })
        verify(cityRepository, times(1)).save(targetCity)
    }

    @Test
    fun `부대탈퇴지시 checks constraints cost and successful run`() {
        val failCmd = che_부대탈퇴지시(createGeneral(officerLevel = 12), env())
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)
        assertTrue((fail as ConstraintResult.Fail).reason.contains("대상 장수"))

        val target = createGeneral(id = 2, nationId = 1)
        target.troopId = 99

        val cmd = che_부대탈퇴지시(createGeneral(officerLevel = 12), env())
        cmd.destGeneral = target

        assertEquals(0, cmd.getCost().gold)
        assertEquals(0, cmd.getCost().rice)
        assertEquals(0, cmd.getPreReqTurn())

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(0L, target.troopId)
        assertTrue(result.logs.any { it.contains("탈퇴") })
    }

    @Test
    fun `인구이동 checks constraints cost and successful run`() {
        val failCmd = cr_인구이동(createGeneral(officerLevel = 12), env(), mapOf("amount" to 20000))
        failCmd.city = createCity(id = 1, nationId = 1)
        failCmd.nation = createNation(id = 1, gold = 10000, rice = 10000)
        val fail = failCmd.checkFullCondition()
        assertTrue(fail is ConstraintResult.Fail)
        assertTrue((fail as ConstraintResult.Fail).reason.contains("목적지 도시"))

        val nation = createNation(id = 1, gold = 10000, rice = 10000)
        val fromCity = createCity(id = 1, nationId = 1)
        fromCity.pop = 50000
        val toCity = createCity(id = 2, nationId = 1)
        toCity.pop = 1000
        toCity.popMax = 60000

        val cmd = cr_인구이동(createGeneral(officerLevel = 12), env(), mapOf("amount" to 20000))
        cmd.city = fromCity
        cmd.destCity = toCity
        cmd.nation = nation
        cmd.constraintEnv = mapOf(
            "mapAdjacency" to mapOf(1L to listOf(2L), 2L to listOf(1L)),
            "cityNationById" to mapOf(1L to 1L, 2L to 1L),
        )

        assertEquals(200, cmd.getCost().gold)
        assertEquals(200, cmd.getCost().rice)
        assertEquals(0, cmd.getPreReqTurn())

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9800, nation.gold)
        assertEquals(9800, nation.rice)
        assertEquals(30000, fromCity.pop)
        assertEquals(21000, toCity.pop)
        assertTrue(result.logs.any { it.contains("인구") })
    }
}
