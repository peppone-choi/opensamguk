package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.nation.che_급습
import com.opensam.command.nation.che_불가침수락
import com.opensam.command.nation.che_불가침제의
import com.opensam.command.nation.che_불가침파기수락
import com.opensam.command.nation.che_불가침파기제의
import com.opensam.command.nation.che_수몰
import com.opensam.command.nation.che_의병모집
import com.opensam.command.nation.che_이호경식
import com.opensam.command.nation.che_종전수락
import com.opensam.command.nation.che_종전제의
import com.opensam.command.nation.che_피장파장
import com.opensam.command.nation.che_허보
import com.opensam.engine.DiplomacyService
import com.opensam.entity.City
import com.opensam.entity.Diplomacy
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
import kotlin.random.Random

class NationDiplomacyStrategicCommandTest {

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
        def: Int = 500,
        wall: Int = 500,
        pop: Int = 10000,
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
        def = def,
        defMax = 1000,
        wall = wall,
        wallMax = 1000,
        pop = pop,
        popMax = 50000,
        trust = 80,
    )

    private fun createNation(
        id: Long = 1,
        level: Short = 7,
        strategicCmdLimit: Short = 0,
        gold: Int = 200000,
        rice: Int = 200000,
    ): Nation = Nation(
        id = id,
        worldId = 1,
        name = "테스트국가$id",
        color = "#FF0000",
        gold = gold,
        rice = rice,
        level = level,
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

    private data class MockServicesBundle(
        val services: CommandServices,
        val generalRepository: GeneralRepository,
        val cityRepository: CityRepository,
        val nationRepository: NationRepository,
        val diplomacyService: DiplomacyService,
    )

    private fun createMockServicesBundle(): MockServicesBundle {
        val generalRepository = mock(GeneralRepository::class.java)
        val cityRepository = mock(CityRepository::class.java)
        val nationRepository = mock(NationRepository::class.java)
        val diplomacyService = mock(DiplomacyService::class.java)

        return MockServicesBundle(
            services = CommandServices(
                generalRepository = generalRepository,
                cityRepository = cityRepository,
                nationRepository = nationRepository,
                diplomacyService = diplomacyService,
            ),
            generalRepository = generalRepository,
            cityRepository = cityRepository,
            nationRepository = nationRepository,
            diplomacyService = diplomacyService,
        )
    }

    @Test
    fun `종전제의 checks chief and run calls diplomacy service`() {
        val nonChief = che_종전제의(createGeneral(officerLevel = 5), env())
        nonChief.city = createCity()
        nonChief.nation = createNation(id = 1)
        nonChief.destNation = createNation(id = 2)
        val failed = nonChief.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_종전제의(general, env())
        val mocks = createMockServicesBundle()
        cmd.services = mocks.services
        cmd.city = createCity(nationId = 1, supplyState = 1)
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        verify(mocks.diplomacyService).proposeCeasefire(1, 1, 2)
        assertEquals(50, general.experience)
        assertEquals(50, general.dedication)
    }

    @Test
    fun `종전수락 checks destination general and run calls diplomacy service`() {
        val cmd1 = che_종전수락(createGeneral(), env())
        cmd1.nation = createNation(id = 1)
        cmd1.destNation = createNation(id = 2)
        val failed = cmd1.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_종전수락(general, env())
        val mocks = createMockServicesBundle()
        cmd.services = mocks.services
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)
        cmd.destGeneral = createGeneral(id = 10, nationId = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        verify(mocks.diplomacyService).acceptCeasefire(1, 1, 2)
        assertEquals(50, general.experience)
        assertEquals(50, general.dedication)
    }

    @Test
    fun `불가침제의 checks destination nation and run calls diplomacy service`() {
        val cmd1 = che_불가침제의(createGeneral(), env())
        cmd1.nation = createNation(id = 1)
        val failed = cmd1.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_불가침제의(general, env())
        val mocks = createMockServicesBundle()
        cmd.services = mocks.services
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        verify(mocks.diplomacyService).proposeNonAggression(1, 1, 2)
        assertEquals(50, general.experience)
        assertEquals(50, general.dedication)
    }

    @Test
    fun `불가침수락 checks occupation and run calls diplomacy service`() {
        val cmd1 = che_불가침수락(createGeneral(), env())
        cmd1.city = createCity(nationId = 2)
        cmd1.nation = createNation(id = 1)
        cmd1.destNation = createNation(id = 2)
        cmd1.destGeneral = createGeneral(id = 10, nationId = 2)
        val failed = cmd1.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_불가침수락(general, env())
        val mocks = createMockServicesBundle()
        cmd.services = mocks.services
        cmd.city = createCity(nationId = 1, supplyState = 1)
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)
        cmd.destGeneral = createGeneral(id = 10, nationId = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        verify(mocks.diplomacyService).acceptNonAggression(1, 1, 2)
        assertEquals(50, general.experience)
        assertEquals(50, general.dedication)
    }

    @Test
    fun `불가침파기제의 checks chief and run calls diplomacy service`() {
        val nonChief = che_불가침파기제의(createGeneral(officerLevel = 5), env())
        nonChief.city = createCity()
        nonChief.nation = createNation(id = 1)
        nonChief.destNation = createNation(id = 2)
        val failed = nonChief.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_불가침파기제의(general, env())
        val mocks = createMockServicesBundle()
        cmd.services = mocks.services
        cmd.city = createCity(nationId = 1, supplyState = 1)
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        verify(mocks.diplomacyService).proposeBreakNonAggression(1, 1, 2)
        assertEquals(50, general.experience)
        assertEquals(50, general.dedication)
    }

    @Test
    fun `불가침파기수락 checks destination general and run calls diplomacy service`() {
        val cmd1 = che_불가침파기수락(createGeneral(), env())
        cmd1.nation = createNation(id = 1)
        cmd1.destNation = createNation(id = 2)
        val failed = cmd1.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_불가침파기수락(general, env())
        val mocks = createMockServicesBundle()
        cmd.services = mocks.services
        cmd.nation = createNation(id = 1)
        cmd.destNation = createNation(id = 2)
        cmd.destGeneral = createGeneral(id = 10, nationId = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        verify(mocks.diplomacyService).acceptBreakNonAggression(1, 1, 2)
        assertEquals(50, general.experience)
        assertEquals(50, general.dedication)
    }

    @Test
    fun `급습 validates strategic metadata constraint and run`() {
        val cmdMeta = che_급습(createGeneral(), env())
        assertEquals(0, cmdMeta.getCost().gold)
        assertEquals(0, cmdMeta.getCost().rice)
        assertEquals(0, cmdMeta.getPreReqTurn())
        assertEquals(40, cmdMeta.getPostReqTurn())

        val nonChief = che_급습(createGeneral(officerLevel = 5), env())
        nonChief.city = createCity(nationId = 1)
        nonChief.nation = createNation(id = 1)
        nonChief.destNation = createNation(id = 2)
        val failed = nonChief.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_급습(general, env())
        val mocks = createMockServicesBundle()
        val relations = listOf(
            Diplomacy(worldId = 1, srcNationId = 2, destNationId = 3, stateCode = "불가침", term = 7),
            Diplomacy(worldId = 1, srcNationId = 2, destNationId = 4, stateCode = "선전포고", term = 0),
        )
        `when`(mocks.diplomacyService.getRelationsForNation(1, 2)).thenReturn(relations)

        val nation = createNation(id = 1)
        cmd.services = mocks.services
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destNation = createNation(id = 2)

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        assertEquals(4, relations[0].term.toInt())
        assertEquals(0, relations[1].term.toInt())
        assertTrue(result.logs.any { it.contains("급습") })
    }

    @Test
    fun `수몰 validates strategic metadata constraint and run`() {
        val cmdMeta = che_수몰(createGeneral(), env())
        assertEquals(0, cmdMeta.getCost().gold)
        assertEquals(0, cmdMeta.getCost().rice)
        assertEquals(2, cmdMeta.getPreReqTurn())
        assertEquals(20, cmdMeta.getPostReqTurn())

        val cmdFail = che_수몰(createGeneral(officerLevel = 12), env())
        cmdFail.city = createCity(nationId = 1)
        cmdFail.nation = createNation(id = 1)
        cmdFail.destCity = createCity(id = 2, nationId = 2)
        cmdFail.constraintEnv = mapOf("atWarNationIds" to emptySet<Long>())
        val failed = cmdFail.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_수몰(general, env())
        val nation = createNation(id = 1)
        val destCity = createCity(id = 2, nationId = 2, def = 800, wall = 600, pop = 10000)
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destCity = destCity
        cmd.constraintEnv = mapOf("atWarNationIds" to setOf(2L))

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        assertEquals(160, destCity.def)
        assertEquals(120, destCity.wall)
        assertEquals(5000, destCity.pop)
        assertEquals(500, destCity.dead)
    }

    @Test
    fun `허보 validates strategic metadata constraint and run`() {
        val cmdMeta = che_허보(createGeneral(), env())
        assertEquals(0, cmdMeta.getCost().gold)
        assertEquals(0, cmdMeta.getCost().rice)
        assertEquals(1, cmdMeta.getPreReqTurn())
        assertEquals(20, cmdMeta.getPostReqTurn())

        val cmdFail = che_허보(createGeneral(), env())
        cmdFail.city = createCity(nationId = 1)
        cmdFail.nation = createNation(id = 1)
        val failed = cmdFail.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val general = createGeneral(officerLevel = 12)
        val cmd = che_허보(general, env())
        val nation = createNation(id = 1)
        val destCity = createCity(id = 2, nationId = 2)
        val enemyGeneral1 = createGeneral(id = 101, nationId = 2, cityId = 2).apply { troopId = 999 }
        val enemyGeneral2 = createGeneral(id = 102, nationId = 2, cityId = 2).apply { troopId = 102 }
        val allyGeneral = createGeneral(id = 201, nationId = 1, cityId = 2)

        val mocks = createMockServicesBundle()
        `when`(mocks.generalRepository.findByCityId(2)).thenReturn(listOf(enemyGeneral1, enemyGeneral2, allyGeneral))
        `when`(mocks.cityRepository.findByNationId(2)).thenReturn(listOf(destCity, createCity(id = 3, nationId = 2)))

        cmd.services = mocks.services
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destCity = destCity

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        assertEquals(3L, enemyGeneral1.cityId)
        assertEquals(3L, enemyGeneral2.cityId)
        assertEquals(0L, enemyGeneral1.troopId)
        assertEquals(102L, enemyGeneral2.troopId)
        verify(mocks.generalRepository, times(2)).save(org.mockito.Mockito.any(General::class.java))
    }

    @Test
    fun `의병모집 validates strategic metadata constraint and run`() {
        val cmdMeta = che_의병모집(createGeneral(), env())
        assertEquals(0, cmdMeta.getCost().gold)
        assertEquals(0, cmdMeta.getCost().rice)
        assertEquals(2, cmdMeta.getPreReqTurn())
        assertEquals(100, cmdMeta.getPostReqTurn())

        val cmdFail = che_의병모집(createGeneral(), env(year = 190, startYear = 190))
        cmdFail.city = createCity(nationId = 1)
        cmdFail.nation = createNation(id = 1)
        val failed = cmdFail.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val cmd = che_의병모집(createGeneral(officerLevel = 12), env(year = 200, startYear = 190))
        val nation = createNation(id = 1)
        val city = createCity(id = 1, nationId = 1, pop = 30000)
        val mocks = createMockServicesBundle()

        cmd.services = mocks.services
        cmd.city = city
        cmd.nation = nation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(15000, city.pop)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        verify(mocks.generalRepository, times(3)).save(org.mockito.Mockito.any(General::class.java))
    }

    @Test
    fun `이호경식 validates strategic metadata constraint and run`() {
        val cmdMeta = che_이호경식(createGeneral(), env())
        assertEquals(0, cmdMeta.getCost().gold)
        assertEquals(0, cmdMeta.getCost().rice)
        assertEquals(0, cmdMeta.getPreReqTurn())
        assertEquals(126, cmdMeta.getPostReqTurn())

        val cmdFail = che_이호경식(createGeneral(), env())
        cmdFail.city = createCity(nationId = 1)
        cmdFail.nation = createNation(id = 1)
        val failed = cmdFail.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val cmd = che_이호경식(createGeneral(officerLevel = 12), env())
        val nation = createNation(id = 1)
        val destNation = createNation(id = 2)
        val targetNation = createNation(id = 3, level = 5)
        val mocks = createMockServicesBundle()
        `when`(mocks.nationRepository.findByWorldId(1)).thenReturn(listOf(nation, destNation, targetNation))

        cmd.services = mocks.services
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destNation = destNation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        verify(mocks.diplomacyService).declareWar(1, 2, 3)
    }

    @Test
    fun `피장파장 validates strategic metadata constraint and run`() {
        val cmdMeta = che_피장파장(createGeneral(), env())
        assertEquals(0, cmdMeta.getCost().gold)
        assertEquals(0, cmdMeta.getCost().rice)
        assertEquals(1, cmdMeta.getPreReqTurn())
        assertEquals(8, cmdMeta.getPostReqTurn())

        val cmdFail = che_피장파장(createGeneral(), env())
        cmdFail.city = createCity(nationId = 1)
        cmdFail.nation = createNation(id = 1, strategicCmdLimit = 2)
        cmdFail.destNation = createNation(id = 2)
        val failed = cmdFail.checkFullCondition()
        assertTrue(failed is ConstraintResult.Fail)

        val cmd = che_피장파장(createGeneral(officerLevel = 12), env())
        val nation = createNation(id = 1, strategicCmdLimit = 0)
        val destNation = createNation(id = 2, strategicCmdLimit = 5)
        cmd.city = createCity(nationId = 1)
        cmd.nation = nation
        cmd.destNation = destNation

        val check = cmd.checkFullCondition()
        assertTrue(check is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertEquals(9, nation.strategicCmdLimit.toInt())
        assertEquals(9, destNation.strategicCmdLimit.toInt())
    }
}
