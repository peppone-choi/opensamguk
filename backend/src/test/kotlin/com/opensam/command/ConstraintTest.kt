package com.opensam.command

import com.opensam.command.constraint.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ConstraintTest {

    private fun createGeneral(
        nationId: Long = 1,
        cityId: Long = 1,
        gold: Int = 1000,
        rice: Int = 1000,
        crew: Int = 100,
        train: Short = 50,
        atmos: Short = 50,
        officerLevel: Short = 0,
        troopId: Long = 0,
        npcState: Short = 0,
        age: Short = 30,
    ): General {
        return General(
            id = 1,
            worldId = 1,
            name = "테스트",
            nationId = nationId,
            cityId = cityId,
            gold = gold,
            rice = rice,
            crew = crew,
            train = train,
            atmos = atmos,
            officerLevel = officerLevel,
            troopId = troopId,
            npcState = npcState,
            age = age,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(
        nationId: Long = 1,
        supplyState: Short = 1,
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
    ): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            supplyState = supplyState,
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
        )
    }

    private fun createNation(
        id: Long = 1,
        gold: Int = 10000,
        rice: Int = 10000,
        level: Short = 1,
        capitalCityId: Long? = 1,
        strategicCmdLimit: Short = 0,
    ): Nation {
        return Nation(
            id = id,
            worldId = 1,
            name = "테스트국가",
            color = "#FF0000",
            gold = gold,
            rice = rice,
            level = level,
            capitalCityId = capitalCityId,
            strategicCmdLimit = strategicCmdLimit,
        )
    }

    private fun ctx(
        general: General = createGeneral(),
        city: City? = null,
        nation: Nation? = null,
        destGeneral: General? = null,
        destCity: City? = null,
        destNation: Nation? = null,
    ) = ConstraintContext(
        general = general,
        city = city,
        nation = nation,
        destGeneral = destGeneral,
        destCity = destCity,
        destNation = destNation,
    )

    // ========== NotBeNeutral ==========

    @Test
    fun `NotBeNeutral passes when general has nation`() {
        val result = NotBeNeutral().test(ctx(general = createGeneral(nationId = 1)))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotBeNeutral fails when general has no nation`() {
        val result = NotBeNeutral().test(ctx(general = createGeneral(nationId = 0)))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("소속 국가"))
    }

    // ========== BeNeutral ==========

    @Test
    fun `BeNeutral passes when general is neutral`() {
        val result = BeNeutral().test(ctx(general = createGeneral(nationId = 0)))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `BeNeutral fails when general has nation`() {
        val result = BeNeutral().test(ctx(general = createGeneral(nationId = 1)))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("재야"))
    }

    // ========== OccupiedCity ==========

    @Test
    fun `OccupiedCity passes when city belongs to general nation`() {
        val general = createGeneral(nationId = 1)
        val city = createCity(nationId = 1)
        val result = OccupiedCity().test(ctx(general = general, city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `OccupiedCity fails when city belongs to different nation`() {
        val general = createGeneral(nationId = 1)
        val city = createCity(nationId = 2)
        val result = OccupiedCity().test(ctx(general = general, city = city))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("아군 도시"))
    }

    @Test
    fun `OccupiedCity fails when no city provided`() {
        val result = OccupiedCity().test(ctx())
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("도시 정보"))
    }

    // ========== SuppliedCity ==========

    @Test
    fun `SuppliedCity passes when city is supplied`() {
        val city = createCity(supplyState = 1)
        val result = SuppliedCity().test(ctx(city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `SuppliedCity fails when city supply is cut`() {
        val city = createCity(supplyState = 0)
        val result = SuppliedCity().test(ctx(city = city))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("보급"))
    }

    // ========== ReqGeneralGold ==========

    @Test
    fun `ReqGeneralGold passes when general has enough gold`() {
        val general = createGeneral(gold = 500)
        val result = ReqGeneralGold(500).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralGold passes when general has more than required`() {
        val general = createGeneral(gold = 1000)
        val result = ReqGeneralGold(500).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralGold fails when general lacks gold`() {
        val general = createGeneral(gold = 99)
        val result = ReqGeneralGold(100).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        val failResult = result as ConstraintResult.Fail
        assertTrue(failResult.reason.contains("자금"))
        assertTrue(failResult.reason.contains("100"))
        assertTrue(failResult.reason.contains("99"))
    }

    // ========== ReqGeneralRice ==========

    @Test
    fun `ReqGeneralRice passes when general has enough rice`() {
        val general = createGeneral(rice = 500)
        val result = ReqGeneralRice(500).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralRice fails when general lacks rice`() {
        val general = createGeneral(rice = 50)
        val result = ReqGeneralRice(100).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        val failResult = result as ConstraintResult.Fail
        assertTrue(failResult.reason.contains("군량"))
    }

    // ========== ReqGeneralCrew ==========

    @Test
    fun `ReqGeneralCrew passes with enough crew`() {
        val general = createGeneral(crew = 100)
        val result = ReqGeneralCrew(100).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralCrew fails with zero crew`() {
        val general = createGeneral(crew = 0)
        val result = ReqGeneralCrew().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("병사"))
    }

    // ========== RemainCityCapacity ==========

    @Test
    fun `RemainCityCapacity passes when agri is below max`() {
        val city = createCity(agri = 500, agriMax = 1000)
        val result = RemainCityCapacity("agri", "농지 개간").test(ctx(city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `RemainCityCapacity fails when agri equals max`() {
        val city = createCity(agri = 1000, agriMax = 1000)
        val result = RemainCityCapacity("agri", "농지 개간").test(ctx(city = city))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("최대치"))
    }

    @Test
    fun `RemainCityCapacity works for comm`() {
        val city = createCity(comm = 1000, commMax = 1000)
        val result = RemainCityCapacity("comm", "상업 투자").test(ctx(city = city))
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `RemainCityCapacity works for wall`() {
        val city = createCity(wall = 999, wallMax = 1000)
        val result = RemainCityCapacity("wall", "성벽 보수").test(ctx(city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    // ========== BeLord / BeChief ==========

    @Test
    fun `BeLord passes for lord`() {
        val general = createGeneral(officerLevel = 12)
        val result = BeLord().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `BeLord fails for non-lord`() {
        val general = createGeneral(officerLevel = 5)
        val result = BeLord().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `BeChief passes for officer level 12 or above`() {
        val general = createGeneral(officerLevel = 12)
        val result = BeChief().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    // ========== NotLord ==========

    @Test
    fun `NotLord passes for non-lord`() {
        val general = createGeneral(officerLevel = 5)
        val result = NotLord().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotLord fails for lord`() {
        val general = createGeneral(officerLevel = 12)
        val result = NotLord().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("군주"))
    }

    // ========== ReqOfficerLevel ==========

    @Test
    fun `ReqOfficerLevel passes when level is sufficient`() {
        val general = createGeneral(officerLevel = 5)
        val result = ReqOfficerLevel(5).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqOfficerLevel fails when level is insufficient`() {
        val general = createGeneral(officerLevel = 3)
        val result = ReqOfficerLevel(5).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("관직"))
    }

    // ========== ReqGeneralTrainMargin / ReqGeneralAtmosMargin ==========

    @Test
    fun `ReqGeneralTrainMargin passes when train is below max`() {
        val general = createGeneral(train = 50)
        val result = ReqGeneralTrainMargin(80).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralTrainMargin fails when train is at max`() {
        val general = createGeneral(train = 80)
        val result = ReqGeneralTrainMargin(80).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `ReqGeneralAtmosMargin passes when atmos is below max`() {
        val general = createGeneral(atmos = 50)
        val result = ReqGeneralAtmosMargin(80).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralAtmosMargin fails when atmos is at max`() {
        val general = createGeneral(atmos = 80)
        val result = ReqGeneralAtmosMargin(80).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
    }

    // ========== MustBeTroopLeader ==========

    @Test
    fun `MustBeTroopLeader passes when general is troop leader`() {
        val general = createGeneral(troopId = 1) // troopId == general.id
        val result = MustBeTroopLeader().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `MustBeTroopLeader passes when troopId is zero`() {
        val general = createGeneral(troopId = 0)
        val result = MustBeTroopLeader().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `MustBeTroopLeader fails when general belongs to another troop`() {
        val general = createGeneral(troopId = 99)
        val result = MustBeTroopLeader().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("부대장"))
    }

    // ========== NotSameDestCity ==========

    @Test
    fun `NotSameDestCity passes when dest city differs`() {
        val general = createGeneral(cityId = 1)
        val destCity = createCity().apply { id = 2 }
        val result = NotSameDestCity().test(ctx(general = general, destCity = destCity))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotSameDestCity fails when dest city is same`() {
        val general = createGeneral(cityId = 1)
        val destCity = createCity().apply { id = 1 }
        val result = NotSameDestCity().test(ctx(general = general, destCity = destCity))
        assertTrue(result is ConstraintResult.Fail)
    }

    // ========== ReqNationGold / ReqNationRice ==========

    @Test
    fun `ReqNationGold passes with enough gold`() {
        val nation = createNation(gold = 5000)
        val result = ReqNationGold(5000).test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqNationGold fails without enough gold`() {
        val nation = createNation(gold = 100)
        val result = ReqNationGold(5000).test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("국고"))
    }

    @Test
    fun `ReqNationRice passes with enough rice`() {
        val nation = createNation(rice = 5000)
        val result = ReqNationRice(5000).test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqNationRice fails without enough rice`() {
        val nation = createNation(rice = 100)
        val result = ReqNationRice(5000).test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("병량"))
    }

    // ========== ExistsDestGeneral / FriendlyDestGeneral ==========

    @Test
    fun `ExistsDestGeneral passes when dest general exists`() {
        val destGeneral = createGeneral()
        val result = ExistsDestGeneral().test(ctx(destGeneral = destGeneral))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ExistsDestGeneral fails when dest general is null`() {
        val result = ExistsDestGeneral().test(ctx())
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `FriendlyDestGeneral passes when same nation`() {
        val general = createGeneral(nationId = 1)
        val destGeneral = createGeneral(nationId = 1)
        val result = FriendlyDestGeneral().test(ctx(general = general, destGeneral = destGeneral))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `FriendlyDestGeneral fails when different nation`() {
        val general = createGeneral(nationId = 1)
        val destGeneral = createGeneral(nationId = 2)
        val result = FriendlyDestGeneral().test(ctx(general = general, destGeneral = destGeneral))
        assertTrue(result is ConstraintResult.Fail)
    }

    // ========== WanderingNation ==========

    @Test
    fun `WanderingNation passes when nation level is 0`() {
        val nation = createNation(level = 0)
        val result = WanderingNation().test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `WanderingNation passes when no nation provided`() {
        val result = WanderingNation().test(ctx())
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `WanderingNation fails when nation has level`() {
        val nation = createNation(level = 3)
        val result = WanderingNation().test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("방랑군"))
    }

    // ========== BeOpeningPart / NotOpeningPart ==========

    @Test
    fun `BeOpeningPart passes during opening`() {
        // relYear < 1 => opening part
        val result = BeOpeningPart(0).test(ctx())
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `BeOpeningPart fails after opening`() {
        val result = BeOpeningPart(5).test(ctx())
        assertTrue(result is ConstraintResult.Fail)
    }

    @Test
    fun `NotOpeningPart passes after opening`() {
        val result = NotOpeningPart(5).test(ctx())
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotOpeningPart fails during opening`() {
        val result = NotOpeningPart(0).test(ctx())
        assertTrue(result is ConstraintResult.Fail)
    }

    // ========== AlwaysFail ==========

    @Test
    fun `AlwaysFail always fails with given reason`() {
        val result = AlwaysFail("사용 불가").test(ctx())
        assertTrue(result is ConstraintResult.Fail)
        assertEquals("사용 불가", (result as ConstraintResult.Fail).reason)
    }

    // ========== MustBeNPC ==========

    @Test
    fun `MustBeNPC passes for NPC`() {
        val general = createGeneral(npcState = 1)
        val result = MustBeNPC().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `MustBeNPC fails for player general`() {
        val general = createGeneral(npcState = 0)
        val result = MustBeNPC().test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("NPC"))
    }

    // ========== NotCapital ==========

    @Test
    fun `NotCapital passes when not at capital`() {
        val general = createGeneral(cityId = 2)
        val nation = createNation(capitalCityId = 1)
        val result = NotCapital().test(ctx(general = general, nation = nation))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotCapital fails when at capital`() {
        val general = createGeneral(cityId = 1)
        val nation = createNation(capitalCityId = 1)
        val result = NotCapital().test(ctx(general = general, nation = nation))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("수도"))
    }

    // ========== AvailableStrategicCommand ==========

    @Test
    fun `AvailableStrategicCommand passes when limit is zero`() {
        val nation = createNation(strategicCmdLimit = 0)
        val result = AvailableStrategicCommand().test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `AvailableStrategicCommand fails when limit is positive`() {
        val nation = createNation(strategicCmdLimit = 5)
        val result = AvailableStrategicCommand().test(ctx(nation = nation))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("전략 명령"))
    }

    // ========== ReqGeneralAge ==========

    @Test
    fun `ReqGeneralAge passes when old enough`() {
        val general = createGeneral(age = 30)
        val result = ReqGeneralAge(20).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralAge fails when too young`() {
        val general = createGeneral(age = 15)
        val result = ReqGeneralAge(20).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("나이"))
    }

    // ========== NeutralCity ==========

    @Test
    fun `NeutralCity passes when city is neutral`() {
        val city = createCity(nationId = 0)
        val result = NeutralCity().test(ctx(city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NeutralCity fails when city is owned`() {
        val city = createCity(nationId = 1)
        val result = NeutralCity().test(ctx(city = city))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("공백지"))
    }

    // ========== RemainCityTrust ==========

    @Test
    fun `RemainCityTrust passes when trust is below max`() {
        val city = createCity(trust = 80)
        val result = RemainCityTrust(100).test(ctx(city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `RemainCityTrust fails when trust is at max`() {
        val city = createCity(trust = 100)
        val result = RemainCityTrust(100).test(ctx(city = city))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("민심"))
    }

    // ========== ReqGeneralStatValue ==========

    @Test
    fun `ReqGeneralStatValue passes when stat is sufficient`() {
        val general = createGeneral()
        general.intel = 80
        val result = ReqGeneralStatValue({ it.intel }, "지력", 50).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `ReqGeneralStatValue fails when stat is insufficient`() {
        val general = createGeneral()
        general.intel = 30
        val result = ReqGeneralStatValue({ it.intel }, "지력", 50).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("지력"))
    }
}
