package com.opensam.command

import com.opensam.command.constraint.ConstraintResult
import com.opensam.command.general.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class GeneralMilitaryCommandTest {

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

    @Test
    fun `출병 should pass constraints and trigger battle`() {
        val general = createTestGeneral(crew = 1000, rice = 100, nationId = 1, cityId = 1)
        val env = createTestEnv()
        env.gameStor["mapAdjacency"] = mapOf(1L to listOf(2L), 2L to listOf(1L))
        env.gameStor["cityNationById"] = mapOf(1L to 1L, 2L to 0L)
        env.gameStor["atWarNationIds"] = emptySet<Long>()

        val cmd = 출병(general, env)
        cmd.city = createTestCity(nationId = 1)
        cmd.nation = createTestNation(id = 1).apply { warState = 0 }
        cmd.destCity = createTestCity(nationId = 0).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"battleTriggered\":true"))
        assertTrue(result.message!!.contains("\"targetCityId\":\"2\""))
        assertTrue(result.message!!.contains("\"rice\":-10"))
    }

    @Test
    fun `출병 should fail when destination is same city`() {
        val general = createTestGeneral(crew = 1000, rice = 100, nationId = 1, cityId = 1)
        val env = createTestEnv()
        val cmd = 출병(general, env)
        cmd.city = createTestCity(nationId = 1)
        cmd.nation = createTestNation(id = 1)
        cmd.destCity = createTestCity(nationId = 0).apply { id = 1 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("같은 도시"))
    }

    @Test
    fun `집합 should fail for non troop leader`() {
        val general = createTestGeneral(nationId = 1, troopId = 2)
        val env = createTestEnv()
        env.gameStor["troopMemberExistsByTroopId"] = mapOf(2L to true)
        val cmd = 집합(general, env)
        cmd.city = createTestCity(nationId = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("부대장"))
    }

    @Test
    fun `집합 should run with troop members`() {
        val general = createTestGeneral(nationId = 1, troopId = 1)
        val env = createTestEnv()
        env.gameStor["troopMemberExistsByTroopId"] = mapOf(1L to true)
        val cmd = 집합(general, env)
        cmd.city = createTestCity(nationId = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"troopAssembly\""))
        assertTrue(result.message!!.contains("\"troopLeaderId\":\"1\""))
    }

    @Test
    fun `귀환 should fail in capital city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1)
        val env = createTestEnv()
        val cmd = 귀환(general, env)
        cmd.nation = createTestNation(id = 1).apply { capitalCityId = 1 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("수도"))
    }

    @Test
    fun `귀환 should move district officer to officer city`() {
        val general = createTestGeneral(nationId = 1, cityId = 5, officerLevel = 3).apply {
            officerCity = 7
        }
        val env = createTestEnv()
        val cmd = 귀환(general, env)
        cmd.nation = createTestNation(id = 1).apply { capitalCityId = 2 }
        cmd.destCity = createTestCity().apply { name = "담당도시" }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"moveTo\":\"7\""))
    }

    @Test
    fun `접경귀환 should fail in occupied city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1)
        val env = createTestEnv()
        val cmd = 접경귀환(general, env)
        cmd.city = createTestCity(nationId = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("아군 도시"))
    }

    @Test
    fun `접경귀환 should move to nearest supplied friendly city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1)
        val env = createTestEnv()
        env.gameStor["mapAdjacency"] = mapOf(
            1L to listOf(2L),
            2L to listOf(1L, 3L),
            3L to listOf(2L),
        )
        env.gameStor["cityNationById"] = mapOf(1L to 2L, 2L to 1L, 3L to 1L)
        env.gameStor["citySupplyStateById"] = mapOf(2L to 1, 3L to 1)

        val cmd = 접경귀환(general, env)
        cmd.city = createTestCity(nationId = 2)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"city\":2"))
    }

    @Test
    fun `강행 should fail when route does not exist`() {
        val general = createTestGeneral(gold = 1000, rice = 1000, cityId = 1)
        val env = createTestEnv()
        val cmd = 강행(general, env)
        cmd.destCity = createTestCity().apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("거리"))
    }

    @Test
    fun `강행 should move city and consume gold`() {
        val general = createTestGeneral(gold = 1000, rice = 1000, cityId = 1, train = 60, atmos = 60)
        val env = createTestEnv(develCost = 100)
        env.gameStor["mapAdjacency"] = mapOf(1L to listOf(2L), 2L to listOf(1L))
        env.gameStor["cityNationById"] = mapOf(1L to 1L, 2L to 1L)

        val cmd = 강행(general, env)
        cmd.destCity = createTestCity().apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"cityId\":\"2\""))
        assertTrue(result.message!!.contains("\"gold\":-500"))
        assertTrue(result.message!!.contains("\"train\":-5"))
        assertTrue(result.message!!.contains("\"atmos\":-5"))
    }

    @Test
    fun `거병 should fail for non neutral general`() {
        val general = createTestGeneral(nationId = 1)
        val env = createTestEnv(year = 189, startYear = 190)
        val cmd = 거병(general, env)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("재야"))
    }

    @Test
    fun `거병 should create wandering nation on success`() {
        val general = createTestGeneral(nationId = 0, cityId = 1)
        val env = createTestEnv(year = 189, startYear = 190)
        val cmd = 거병(general, env)
        cmd.city = createTestCity(nationId = 0)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"createWanderingNation\":true"))
        assertTrue(result.message!!.contains("\"officerLevel\":12"))
    }

    @Test
    fun `전투태세 should fail when train margin is insufficient`() {
        val general = createTestGeneral(nationId = 1, crew = 1000, train = 70, atmos = 50, gold = 1000)
        val env = createTestEnv()
        val cmd = 전투태세(general, env)
        cmd.city = createTestCity(nationId = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("훈련"))
    }

    @Test
    fun `전투태세 should set minimum train and atmos on run`() {
        val general = createTestGeneral(nationId = 1, crew = 1000, crewType = 1, train = 50, atmos = 50, gold = 1000)
        val env = createTestEnv()
        val cmd = 전투태세(general, env)
        cmd.city = createTestCity(nationId = 1)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"setMin\":75"))
        assertTrue(result.message!!.contains("\"leadershipExp\":3"))
    }

    @Test
    fun `화계 should fail against neutral destination city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, intel = 100)
        val env = createTestEnv()
        val cmd = 화계(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 0).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("공백지"))
    }

    @Test
    fun `화계 should execute with fixed rng and consume resources`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, intel = 100)
        val env = createTestEnv()
        val cmd = 화계(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 2, agri = 600, comm = 600).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"gold\":-25"))
        assertTrue(result.message!!.contains("\"rice\":-25"))
        assertTrue(result.message!!.contains("\"destCityChanges\""))
    }

    @Test
    fun `첩보 should fail for friendly destination city`() {
        val general = createTestGeneral(nationId = 1, gold = 100, rice = 100)
        val env = createTestEnv()
        val cmd = 첩보(general, env)
        cmd.destCity = createTestCity(nationId = 1).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("아군 도시"))
    }

    @Test
    fun `첩보 should return spy result and consume resources`() {
        val general = createTestGeneral(nationId = 1, gold = 100, rice = 100)
        val env = createTestEnv()
        val cmd = 첩보(general, env)
        cmd.destCity = createTestCity(nationId = 2, pop = 12000, trust = 75f).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"spyResult\""))
        assertTrue(result.message!!.contains("\"gold\":-15"))
        assertTrue(result.message!!.contains("\"rice\":-15"))
    }

    @Test
    fun `선동 should fail against neutral destination city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, leadership = 100)
        val env = createTestEnv()
        val cmd = 선동(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 0).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("공백지"))
    }

    @Test
    fun `선동 should succeed and modify secu trust with fixed rng`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, leadership = 100)
        val env = createTestEnv()
        val cmd = 선동(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 2, secu = 700, trust = 90f).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"destCityChanges\""))
        assertTrue(result.message!!.contains("\"secu\""))
        assertTrue(result.message!!.contains("\"trust\""))
    }

    @Test
    fun `탈취 should fail against neutral destination city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, strength = 100)
        val env = createTestEnv()
        val cmd = 탈취(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 0).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("공백지"))
    }

    @Test
    fun `탈취 should succeed and include destination city changes`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, strength = 100)
        val env = createTestEnv()
        val cmd = 탈취(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 2, agri = 700, agriMax = 1000, comm = 700, commMax = 1000).apply {
            id = 2
            level = 3
        }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"destCityChanges\""))
        assertTrue(result.message!!.contains("\"comm\""))
        assertTrue(result.message!!.contains("\"agri\""))
    }

    @Test
    fun `파괴 should fail against neutral destination city`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, strength = 100)
        val env = createTestEnv()
        val cmd = 파괴(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 0).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("공백지"))
    }

    @Test
    fun `파괴 should succeed and reduce defense and wall`() {
        val general = createTestGeneral(nationId = 1, cityId = 1, gold = 1000, rice = 1000, strength = 100)
        val env = createTestEnv()
        val cmd = 파괴(general, env)
        cmd.city = createTestCity(nationId = 1, supplyState = 1)
        cmd.destCity = createTestCity(nationId = 2, def = 700, wall = 700).apply { id = 2 }

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"destCityChanges\""))
        assertTrue(result.message!!.contains("\"def\""))
        assertTrue(result.message!!.contains("\"wall\""))
    }

    @Test
    fun `방랑 should fail for non lord`() {
        val general = createTestGeneral(nationId = 1, officerLevel = 5)
        val env = createTestEnv()
        val cmd = 방랑(general, env)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Fail)
        assertTrue((cond as ConstraintResult.Fail).reason.contains("군주"))
    }

    @Test
    fun `방랑 should succeed and become wandering nation`() {
        val general = createTestGeneral(nationId = 1, officerLevel = 12)
        val env = createTestEnv(year = 200, startYear = 190)
        val cmd = 방랑(general, env)

        val cond = cmd.checkFullCondition()
        assertTrue(cond is ConstraintResult.Pass)

        val result = runBlocking { cmd.run(fixedRng) }
        assertTrue(result.success)
        assertTrue(result.message!!.contains("\"becomeWanderer\":true"))
        assertTrue(result.message!!.contains("\"releaseAllCities\":true"))
    }
}
