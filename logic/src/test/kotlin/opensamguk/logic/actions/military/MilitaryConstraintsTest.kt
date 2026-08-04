package opensamguk.logic.actions.military

import opensamguk.common.constants.GameConst
import opensamguk.logic.constraints.*
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import opensamguk.logic.world.CalcCityDistance
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * MIL1 — military commands CONSUME the 11 C-PURE/C-DEST presets (they are DEFINED there, not here).
 * This pins that the CONSUMED presets, wired into each military command's PHP constraint list, deny
 * + allow with the exact PHP reason strings and the correct FIRST-DENY order.
 *
 *   - 징병/모병 fullConditionConstraints (che_징병.php:115-124): NotBeNeutral, OccupiedCity,
 *     ReqCityCapacity('pop','주민',…), ReqCityTrust(20), ReqGeneralGold, ReqGeneralRice,
 *     ReqGeneralCrewMargin, AvailableRecruitCrewType.
 *   - 훈련/맹훈련 (che_훈련.php:49-55): + ReqGeneralCrew + ReqGeneralTrainMargin(maxTrainByCommand).
 *   - 사기진작 (che_사기진작.php:50-58): + ReqGeneralAtmosMargin(maxAtmosByCommand).
 *   - 집합 (che_집합.php:39-45): NotBeNeutral, OccupiedCity, SuppliedCity, MustBeTroopLeader, ReqTroopMembers.
 *   - 이동 (che_이동.php:61-66): NotSameDestCity, NearCity(1), ReqGeneralGold, ReqGeneralRice.
 */
class MilitaryConstraintsTest {

    private fun general(
        id: Int = 1, nationId: Int = 1, cityId: Int = 5,
        gold: Int = 100000, rice: Int = 100000, crew: Int = 1000,
        train: Double = 40.0, atmos: Double = 40.0, crewTypeId: Int = 1100, troop: Int = 1,
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 70, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = gold, rice = rice, crew = crew, train = train, atmos = atmos,
        crewTypeId = crewTypeId, troop = troop,
    )

    private fun city(
        id: Int = 5, nationId: Int = 1, population: Int = 100000,
        trust: Double = 50.0, supplyState: Int = 1,
    ) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 100, commerceMax = 1000, agriculture = 100, agricultureMax = 1000,
        supplyState = supplyState, frontState = 0, trust = trust,
        population = population, populationMax = 200000,
    )

    private val nation = Nation(id = 1, level = 7, capitalCityId = 5)

    private fun view(g: General = general(), c: City = city(), dest: City? = null) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = listOfNotNull(c, dest).associateBy { it.id },
        nations = mapOf(nation.id to nation),
        env = emptyMap(),
    )

    private fun ctx(actorId: Int = 1, cityId: Int? = 5, nationId: Int? = 1, destCityId: Int? = null) =
        ConstraintContext(actorId = actorId, cityId = cityId, nationId = nationId,
            destCityId = destCityId, mode = ConstraintMode.FULL)

    // 징병/모병 full list (che_징병.php:115-124). reqCrew=100 → minAvailableRecruitPop+100 = 30100.
    private fun recruitConstraints(reqCrew: Int = 100, reqCrewTypeId: Int = 1100, recruitable: Boolean = true) = listOf(
        notBeNeutral(),
        occupiedCity(),
        reqCityCapacity("pop", "주민", GameConst.minAvailableRecruitPop + reqCrew),
        reqCityTrust { _, _ -> 20.0 },
        reqGeneralGold { _, _ -> 0 },
        reqGeneralRice { _, _ -> 0 },
        reqGeneralCrewMargin(reqCrewTypeId, { _, _ -> 9999 }, { _, _ -> 70 }),  // different curType → Allow
        availableRecruitCrewType(reqCrewTypeId) { _, _ -> recruitable },
    )

    // 훈련/맹훈련 full list (che_훈련.php:49-55).
    private fun trainConstraints() = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(),
        reqGeneralCrew(),
        reqGeneralTrainMargin { _, _ -> GameConst.maxTrainByCommand },
    )

    // 사기진작 full list (che_사기진작.php:50-58).
    private fun moraleConstraints() = listOf(
        notBeNeutral(), notWanderingNation(), occupiedCity(),
        reqGeneralCrew(),
        reqGeneralGold { _, _ -> 0 }, reqGeneralRice { _, _ -> 0 },
        reqGeneralAtmosMargin { _, _ -> GameConst.maxAtmosByCommand },
    )

    // 집합 full list (che_집합.php:39-45).
    private fun gatherConstraints(hasMember: Boolean) = listOf(
        notBeNeutral(), occupiedCity(), suppliedCity(),
        mustBeTroopLeader(),
        reqTroopMembers { _, _ -> hasMember },
    )

    // === 징병 ===

    @Test
    fun `recruit allows for a valid general`() {
        assertEquals(ConstraintResult.Allow, evaluateConstraints(recruitConstraints(), ctx(), view()))
    }

    @Test
    fun `recruit denies on insufficient population — ReqCityCapacity 주민 josa 이`() {
        val r = evaluateConstraints(recruitConstraints(reqCrew = 100), ctx(), view(c = city(population = 30000)))
        assertEquals(ConstraintResult.Deny("주민이 부족합니다.", "ReqCityCapacity"), r)
    }

    @Test
    fun `recruit denies on low trust — ReqCityTrust`() {
        val r = evaluateConstraints(recruitConstraints(), ctx(), view(c = city(trust = 10.0)))
        assertEquals(ConstraintResult.Deny("민심이 낮아 주민들이 도망갑니다.", "ReqCityTrust"), r)
    }

    @Test
    fun `recruit denies an unavailable crewType — AvailableRecruitCrewType`() {
        val r = evaluateConstraints(recruitConstraints(recruitable = false), ctx(), view())
        assertEquals(ConstraintResult.Deny("현재 선택할 수 없는 병종입니다.", "AvailableRecruitCrewType"), r)
    }

    @Test
    fun `recruit first-deny order — neutral beats trust`() {
        // both NotBeNeutral and ReqCityTrust would fail; NotBeNeutral is first.
        val r = evaluateConstraints(recruitConstraints(), ctx(),
            view(g = general(nationId = 0), c = city(trust = 10.0)))
        assertEquals(ConstraintResult.Deny("재야입니다.", "NotBeNeutral"), r)
    }

    @Test
    fun `recruit crewMargin denies when same crewType and leadership100 not over crew`() {
        // same crewType (1100) AND leadership*100 (70*100=7000) <= crew (8000) → deny
        val list = recruitConstraints(reqCrewTypeId = 1100).toMutableList()
        list[6] = reqGeneralCrewMargin(1100, { _, _ -> 1100 }, { _, _ -> 70 })
        val r = evaluateConstraints(list, ctx(), view(g = general(crew = 8000, crewTypeId = 1100)))
        assertEquals(ConstraintResult.Deny("이미 많은 병력을 보유하고 있습니다.", "ReqGeneralCrewMargin"), r)
    }

    // === 훈련 ===

    @Test
    fun `train allows then denies on no crew — ReqGeneralCrew`() {
        assertEquals(ConstraintResult.Allow, evaluateConstraints(trainConstraints(), ctx(), view()))
        val r = evaluateConstraints(trainConstraints(), ctx(), view(g = general(crew = 0)))
        assertEquals(ConstraintResult.Deny("병사가 모자랍니다.", "ReqGeneralCrew"), r)
    }

    @Test
    fun `train denies at max train — ReqGeneralTrainMargin`() {
        val r = evaluateConstraints(trainConstraints(), ctx(), view(g = general(train = 100.0)))
        assertEquals(ConstraintResult.Deny("병사들은 이미 정예병사들입니다.", "ReqGeneralTrainMargin"), r)
    }

    // === 사기진작 ===

    @Test
    fun `morale denies at max atmos — ReqGeneralAtmosMargin`() {
        val r = evaluateConstraints(moraleConstraints(), ctx(), view(g = general(atmos = 100.0)))
        assertEquals(ConstraintResult.Deny("이미 사기는 하늘을 찌를듯 합니다.", "ReqGeneralAtmosMargin"), r)
    }

    // === 집합 ===

    @Test
    fun `gather allows with troop members and denies a non-leader — MustBeTroopLeader`() {
        // leader: id == troop
        assertEquals(ConstraintResult.Allow,
            evaluateConstraints(gatherConstraints(hasMember = true), ctx(), view(g = general(id = 1, troop = 1))))
        // not leader: id (1) != troop (2)
        val r = evaluateConstraints(gatherConstraints(hasMember = true), ctx(), view(g = general(id = 1, troop = 2)))
        assertEquals(ConstraintResult.Deny("부대장이 아닙니다.", "MustBeTroopLeader"), r)
    }

    @Test
    fun `gather denies with no members — ReqTroopMembers`() {
        val r = evaluateConstraints(gatherConstraints(hasMember = false), ctx(), view(g = general(id = 1, troop = 1)))
        assertEquals(ConstraintResult.Deny("집합 가능한 부대원이 없습니다.", "ReqTroopMembers"), r)
    }

    @Test
    fun `gather command denies when the preloaded general list has no fellow troop member`() {
        val command = CheJiphap(opensamguk.logic.stats.GeneralActionPipeline())
        val r = evaluateConstraints(
            command.buildConstraints(ctx()),
            ctx(),
            view(g = general(id = 1, troop = 1)),
        )
        assertEquals(ConstraintResult.Deny("집합 가능한 부대원이 없습니다.", "ReqTroopMembers"), r)
    }

    @Test
    fun `gather denies an isolated city — SuppliedCity`() {
        val r = evaluateConstraints(gatherConstraints(hasMember = true), ctx(), view(c = city(supplyState = 0)))
        assertEquals(ConstraintResult.Deny("고립된 도시입니다.", "SuppliedCity"), r)
    }

    // === 이동 (NotSameDestCity + NearCity over the F-MAP module) ===

    @Test
    fun `move denies same dest city — NotSameDestCity`() {
        // dest == current city → deny
        val moveList = listOf(notSameDestCity(), nearCity(1) { _, _ -> emptySet() })
        val r = evaluateConstraints(moveList, ctx(destCityId = 5),
            view(c = city(id = 5), dest = city(id = 5)))
        assertEquals(ConstraintResult.Deny("같은 도시입니다.", "NotSameDestCity"), r)
    }

    @Test
    fun `move NearCity arg1 denies a non-adjacent city — 인접도시가 아닙니다`() {
        // dest 99 is NOT in the reachable-within-1 set → arg==1 reason
        val moveList = listOf(notSameDestCity(), nearCity(1) { _, _ -> setOf(2, 3) })
        val r = evaluateConstraints(moveList, ctx(destCityId = 99),
            view(c = city(id = 5), dest = city(id = 99, nationId = 0)))
        assertEquals(ConstraintResult.Deny("인접도시가 아닙니다.", "NearCity"), r)
    }

    @Test
    fun `move NearCity consumes the F-MAP nearCity neighbour set`() {
        // The F-MAP module supplies the reachable set; an adjacent dest passes.
        val from = 5
        val reachable = CalcCityDistance.nearCity(from, 1)   // real F-MAP neighbours of city 5
        // pick any real neighbour as dest; if city 5 has neighbours assert pass, else skip the body
        val destId = reachable.firstOrNull()
        if (destId != null) {
            val moveList = listOf(notSameDestCity(), nearCity(1) { _, _ -> reachable })
            val r = evaluateConstraints(moveList, ctx(destCityId = destId),
                view(c = city(id = from), dest = city(id = destId, nationId = 0)))
            assertEquals(ConstraintResult.Allow, r)
        }
    }
}
