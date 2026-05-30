package opensamguk.logic.constraints

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CP1 — byte-for-byte reason strings for the ~30 pure state presets.
 *
 * Port oracle = PHP Constraint/[Name].php (reason strings, REQ_VALUES, test order). The handful of
 * confirmed TS divergences (MustBeNPC, BeNeutral, WanderingNation-no-trailing-period) get explicit
 * assertions so a future TS-shaped "fix" is caught.
 */
class PresetsPureTest {

    private fun general(
        id: Int = 1,
        nationId: Int = 1,
        cityId: Int = 5,
        officerLevel: Int = 0,
        npcType: Int = 0,
        troop: Int = 0,
        crew: Int = 100,
        train: Double = 50.0,
        atmos: Double = 50.0,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = 1000, rice = 1000,
        crew = crew, train = train, atmos = atmos, troop = troop,
        npcType = npcType, meta = meta,
    )

    private fun city(
        id: Int = 5,
        nationId: Int = 1,
        level: Int = 5,
        supplyState: Int = 1,
        trust: Double = 50.0,
        trade: Int? = 100,
    ) = City(
        id = id, nationId = nationId, level = level,
        commerce = 100, commerceMax = 1000,
        agriculture = 100, agricultureMax = 1000,
        supplyState = supplyState, frontState = 0, trust = trust,
        trade = trade,
    )

    private fun nation(
        id: Int = 1,
        level: Int = 7,
        capitalCityId: Int? = 5,
        gold: Int = 1000,
        rice: Int = 1000,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = Nation(id = id, level = level, capitalCityId = capitalCityId, gold = gold, rice = rice, meta = meta)

    private fun view(
        g: General = general(),
        c: City = city(),
        n: Nation = nation(),
        env: Map<String, Any?> = emptyMap(),
    ) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = mapOf(c.id to c),
        nations = mapOf(n.id to n),
        env = env,
    )

    private fun ctx(
        actorId: Int = 1,
        cityId: Int? = 5,
        nationId: Int? = 1,
        args: Map<String, Any?> = emptyMap(),
        env: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(actorId = actorId, cityId = cityId, nationId = nationId, args = args, env = env, mode = ConstraintMode.FULL)

    private fun deny(r: ConstraintResult): String {
        assertTrue(r is ConstraintResult.Deny, "expected Deny but was $r")
        return (r as ConstraintResult.Deny).reason
    }

    // --- beNeutral (TS-divergent reason) ---
    @Test fun `beNeutral allows when neutral`() =
        assertEquals(ConstraintResult.Allow, beNeutral().test(ctx(), view(g = general(nationId = 0))))
    @Test fun `beNeutral denies when has nation with PHP reason`() =
        assertEquals("재야가 아닙니다.", deny(beNeutral().test(ctx(), view(g = general(nationId = 1)))))

    // --- beChief / notChief ---
    @Test fun `beChief allows officer over 4`() =
        assertEquals(ConstraintResult.Allow, beChief().test(ctx(), view(g = general(officerLevel = 5))))
    @Test fun `beChief denies officer at or below 4`() =
        assertEquals("수뇌가 아닙니다.", deny(beChief().test(ctx(), view(g = general(officerLevel = 4)))))
    @Test fun `notChief allows officer at or below 4`() =
        assertEquals(ConstraintResult.Allow, notChief().test(ctx(), view(g = general(officerLevel = 4))))
    @Test fun `notChief denies officer over 4`() =
        assertEquals("수뇌입니다.", deny(notChief().test(ctx(), view(g = general(officerLevel = 5)))))

    // --- beLord / notLord ---
    @Test fun `beLord allows officer 12`() =
        assertEquals(ConstraintResult.Allow, beLord().test(ctx(), view(g = general(officerLevel = 12))))
    @Test fun `beLord denies non-12`() =
        assertEquals("군주가 아닙니다.", deny(beLord().test(ctx(), view(g = general(officerLevel = 11)))))
    @Test fun `notLord allows non-12`() =
        assertEquals(ConstraintResult.Allow, notLord().test(ctx(), view(g = general(officerLevel = 11))))
    @Test fun `notLord denies officer 12`() =
        assertEquals("군주입니다.", deny(notLord().test(ctx(), view(g = general(officerLevel = 12)))))

    // --- mustBeNPC (TS-divergent reason) ---
    @Test fun `mustBeNPC allows npc 2 or more`() =
        assertEquals(ConstraintResult.Allow, mustBeNPC().test(ctx(), view(g = general(npcType = 2))))
    @Test fun `mustBeNPC denies npc below 2 with PHP reason`() =
        assertEquals("NPC여야 합니다.", deny(mustBeNPC().test(ctx(), view(g = general(npcType = 1)))))

    // --- mustBeTroopLeader (no == troop) ---
    @Test fun `mustBeTroopLeader allows when general id equals troop`() =
        assertEquals(ConstraintResult.Allow, mustBeTroopLeader().test(ctx(), view(g = general(id = 1, troop = 1))))
    @Test fun `mustBeTroopLeader denies when id differs from troop`() =
        assertEquals("부대장이 아닙니다.", deny(mustBeTroopLeader().test(ctx(), view(g = general(id = 1, troop = 2)))))

    // --- reqTroopMembers (preloaded predicate; PHP queries DB for a fellow member) ---
    @Test fun `reqTroopMembers allows when a fellow member exists`() =
        assertEquals(ConstraintResult.Allow, reqTroopMembers { _, _ -> true }.test(ctx(), view()))
    @Test fun `reqTroopMembers denies when no member`() =
        assertEquals("집합 가능한 부대원이 없습니다.", deny(reqTroopMembers { _, _ -> false }.test(ctx(), view())))

    // --- reqGeneralCrew ---
    @Test fun `reqGeneralCrew allows crew over 0`() =
        assertEquals(ConstraintResult.Allow, reqGeneralCrew().test(ctx(), view(g = general(crew = 1))))
    @Test fun `reqGeneralCrew denies crew 0`() =
        assertEquals("병사가 모자랍니다.", deny(reqGeneralCrew().test(ctx(), view(g = general(crew = 0)))))

    // --- reqGeneralAtmosMargin (general.atmos < arg → allow) ---
    @Test fun `reqGeneralAtmosMargin allows when atmos below max`() =
        assertEquals(ConstraintResult.Allow, reqGeneralAtmosMargin { _, _ -> 100 }.test(ctx(), view(g = general(atmos = 50.0))))
    @Test fun `reqGeneralAtmosMargin denies when atmos at or above max`() =
        assertEquals("이미 사기는 하늘을 찌를듯 합니다.", deny(reqGeneralAtmosMargin { _, _ -> 100 }.test(ctx(), view(g = general(atmos = 100.0)))))

    // --- reqGeneralTrainMargin ---
    @Test fun `reqGeneralTrainMargin allows when train below max`() =
        assertEquals(ConstraintResult.Allow, reqGeneralTrainMargin { _, _ -> 100 }.test(ctx(), view(g = general(train = 50.0))))
    @Test fun `reqGeneralTrainMargin denies when train at or above max`() =
        assertEquals("병사들은 이미 정예병사들입니다.", deny(reqGeneralTrainMargin { _, _ -> 100 }.test(ctx(), view(g = general(train = 100.0)))))

    // --- reqGeneralCrewMargin (effective leadership*100 > crew OR different crewtype → allow) ---
    @Test fun `reqGeneralCrewMargin allows when requested crewtype differs from current`() =
        assertEquals(
            ConstraintResult.Allow,
            reqGeneralCrewMargin(reqCrewTypeId = 1, curCrewTypeId = { _, _ -> 2 }, leadership = { _, _ -> 1 })
                .test(ctx(), view(g = general(crew = 100000))),
        )
    @Test fun `reqGeneralCrewMargin allows when leadership margin exceeds crew`() =
        assertEquals(
            ConstraintResult.Allow,
            reqGeneralCrewMargin(reqCrewTypeId = 1, curCrewTypeId = { _, _ -> 1 }, leadership = { _, _ -> 50 })
                .test(ctx(), view(g = general(crew = 4000))),
        )
    @Test fun `reqGeneralCrewMargin denies when same crewtype and crew saturates leadership`() =
        assertEquals(
            "이미 많은 병력을 보유하고 있습니다.",
            deny(
                reqGeneralCrewMargin(reqCrewTypeId = 1, curCrewTypeId = { _, _ -> 1 }, leadership = { _, _ -> 50 })
                    .test(ctx(), view(g = general(crew = 5000))),
            ),
        )

    // --- allowJoinAction (meta.makelimit == 0 → allow) ---
    @Test fun `allowJoinAction allows when makelimit is 0`() =
        assertEquals(ConstraintResult.Allow, allowJoinAction().test(ctx(), view(g = general(meta = linkedMapOf("makelimit" to 0)))))
    @Test fun `allowJoinAction denies with joinActionLimit turn count`() =
        assertEquals("재야가 된지 12턴이 지나야 합니다.", deny(allowJoinAction().test(ctx(), view(g = general(meta = linkedMapOf("makelimit" to 3))))))

    // --- noPenalty (penalty map keyed by penaltyKey) ---
    @Test fun `noPenalty allows when key absent`() =
        assertEquals(ConstraintResult.Allow, noPenalty("betray").test(ctx(), view(g = general(meta = linkedMapOf("penalty" to linkedMapOf<String, Any?>())))))
    @Test fun `noPenalty denies with reason text from penalty map`() =
        assertEquals(
            "징계 사유: 반란",
            deny(noPenalty("betray").test(ctx(), view(g = general(meta = linkedMapOf("penalty" to linkedMapOf("betray" to "반란")))))),
        )

    // --- wanderingNation (TS-divergent: NO trailing period) ---
    @Test fun `wanderingNation allows when level 0`() =
        assertEquals(ConstraintResult.Allow, wanderingNation().test(ctx(), view(n = nation(level = 0))))
    @Test fun `wanderingNation denies with no trailing period`() =
        assertEquals("방랑군이어야 합니다", deny(wanderingNation().test(ctx(), view(n = nation(level = 7)))))

    // --- notCapital (arg = ignoreOfficer) ---
    @Test fun `notCapital allows when city is not capital`() =
        assertEquals(ConstraintResult.Allow, notCapital(ignoreOfficer = false).test(ctx(cityId = 6), view(g = general(cityId = 6), n = nation(capitalCityId = 5))))
    @Test fun `notCapital denies when city is capital`() =
        assertEquals("이미 수도입니다.", deny(notCapital(ignoreOfficer = false).test(ctx(), view(g = general(cityId = 5), n = nation(capitalCityId = 5)))))
    @Test fun `notCapital ignoreOfficer allows capital for officer level 2 to 4`() =
        assertEquals(ConstraintResult.Allow, notCapital(ignoreOfficer = true).test(ctx(), view(g = general(cityId = 5, officerLevel = 3), n = nation(capitalCityId = 5))))
    @Test fun `notCapital ignoreOfficer still denies capital for officer level 5`() =
        assertEquals("이미 수도입니다.", deny(notCapital(ignoreOfficer = true).test(ctx(), view(g = general(cityId = 5, officerLevel = 5), n = nation(capitalCityId = 5)))))

    // --- occupiedCity (allowNeutral branch added) ---
    @Test fun `occupiedCity allows when city nation matches`() =
        assertEquals(ConstraintResult.Allow, occupiedCity(allowNeutral = false).test(ctx(), view(g = general(nationId = 1), c = city(nationId = 1))))
    @Test fun `occupiedCity denies foreign city`() =
        assertEquals("아국이 아닙니다.", deny(occupiedCity(allowNeutral = false).test(ctx(), view(g = general(nationId = 1), c = city(nationId = 2)))))
    @Test fun `occupiedCity allowNeutral allows when general is neutral`() =
        assertEquals(ConstraintResult.Allow, occupiedCity(allowNeutral = true).test(ctx(), view(g = general(nationId = 0), c = city(nationId = 2))))
    @Test fun `occupiedCity allowNeutral denies foreign city for non-neutral`() =
        assertEquals("아국이 아닙니다.", deny(occupiedCity(allowNeutral = true).test(ctx(), view(g = general(nationId = 1), c = city(nationId = 2)))))

    // --- notOccupiedCity ---
    @Test fun `notOccupiedCity allows when city is foreign`() =
        assertEquals(ConstraintResult.Allow, notOccupiedCity().test(ctx(), view(g = general(nationId = 1), c = city(nationId = 2))))
    @Test fun `notOccupiedCity denies own city`() =
        assertEquals("아국입니다.", deny(notOccupiedCity().test(ctx(), view(g = general(nationId = 1), c = city(nationId = 1)))))

    // --- neutralCity ---
    @Test fun `neutralCity allows when city nation is 0`() =
        assertEquals(ConstraintResult.Allow, neutralCity().test(ctx(), view(c = city(nationId = 0))))
    @Test fun `neutralCity denies occupied city`() =
        assertEquals("공백지가 아닙니다.", deny(neutralCity().test(ctx(), view(c = city(nationId = 2)))))

    // --- constructableCity ---
    @Test fun `constructableCity allows neutral level 5`() =
        assertEquals(ConstraintResult.Allow, constructableCity().test(ctx(), view(c = city(nationId = 0, level = 5))))
    @Test fun `constructableCity allows neutral level 6`() =
        assertEquals(ConstraintResult.Allow, constructableCity().test(ctx(), view(c = city(nationId = 0, level = 6))))
    @Test fun `constructableCity denies occupied city`() =
        assertEquals("공백지가 아닙니다.", deny(constructableCity().test(ctx(), view(c = city(nationId = 2, level = 5)))))
    @Test fun `constructableCity denies neutral wrong level`() =
        assertEquals("중, 소 도시에만 가능합니다.", deny(constructableCity().test(ctx(), view(c = city(nationId = 0, level = 4)))))

    // --- reqCityTrust ---
    @Test fun `reqCityTrust allows when trust meets min`() =
        assertEquals(ConstraintResult.Allow, reqCityTrust { _, _ -> 50.0 }.test(ctx(), view(c = city(trust = 50.0))))
    @Test fun `reqCityTrust denies when trust below min`() =
        assertEquals("민심이 낮아 주민들이 도망갑니다.", deny(reqCityTrust { _, _ -> 50.0 }.test(ctx(), view(c = city(trust = 49.0)))))

    // --- reqCityTrader (trade != null || arg >= 2 → allow) ---
    @Test fun `reqCityTrader allows when city has a trader`() =
        assertEquals(ConstraintResult.Allow, reqCityTrader { _, _ -> 0 }.test(ctx(), view(c = city(trade = 100))))
    @Test fun `reqCityTrader allows when npcType arg is 2 or more`() =
        assertEquals(ConstraintResult.Allow, reqCityTrader { _, _ -> 2 }.test(ctx(), view(c = city(trade = null))))
    @Test fun `reqCityTrader denies when no trader and arg below 2`() =
        assertEquals("도시에 상인이 없습니다.", deny(reqCityTrader { _, _ -> 1 }.test(ctx(), view(c = city(trade = null)))))

    // --- remainCityTrust (josa-eun) ---
    @Test fun `remainCityTrust allows when trust below 100`() =
        assertEquals(ConstraintResult.Allow, remainCityTrust("민심").test(ctx(), view(c = city(trust = 99.0))))
    @Test fun `remainCityTrust denies at 100 with eun josa`() =
        assertEquals("민심은 충분합니다.", deny(remainCityTrust("민심").test(ctx(), view(c = city(trust = 100.0)))))

    // --- battleGroundCity (preloaded enemy predicate; PHP queries diplomacy) ---
    @Test fun `battleGroundCity allows when dest is neutral`() =
        assertEquals(ConstraintResult.Allow, battleGroundCity { _, _ -> false }.test(ctx(), view(c = city(nationId = 0))))
    @Test fun `battleGroundCity allows when at war with dest`() =
        assertEquals(ConstraintResult.Allow, battleGroundCity { _, _ -> true }.test(ctx(), view(c = city(nationId = 2))))
    @Test fun `battleGroundCity denies when not at war with dest`() =
        assertEquals("교전중인 국가의 도시가 아닙니다.", deny(battleGroundCity { _, _ -> false }.test(ctx(), view(c = city(nationId = 2)))))

    // --- beOpeningPart / notOpeningPart (relYear vs openingPartYear=3) ---
    @Test fun `beOpeningPart allows when relYear below opening year`() =
        assertEquals(ConstraintResult.Allow, beOpeningPart { _, _ -> 2 }.test(ctx(), view()))
    @Test fun `beOpeningPart denies when relYear at opening year`() =
        assertEquals("초반이 지났습니다.", deny(beOpeningPart { _, _ -> 3 }.test(ctx(), view())))
    @Test fun `notOpeningPart allows when relYear at opening year`() =
        assertEquals(ConstraintResult.Allow, notOpeningPart { _, _ -> 3 }.test(ctx(), view()))
    @Test fun `notOpeningPart denies when relYear below opening year`() =
        assertEquals("초반 제한 중에는 불가능합니다.", deny(notOpeningPart { _, _ -> 2 }.test(ctx(), view())))

    // --- allowWar / allowStrategicCommand (meta.war == 0 → allow) ---
    @Test fun `allowWar allows when nation war is 0`() =
        assertEquals(ConstraintResult.Allow, allowWar().test(ctx(), view(n = nation(meta = linkedMapOf("war" to 0)))))
    @Test fun `allowWar denies when nation war is non-zero`() =
        assertEquals("현재 전쟁 금지입니다.", deny(allowWar().test(ctx(), view(n = nation(meta = linkedMapOf("war" to 1))))))
    @Test fun `allowStrategicCommand allows when nation war is 0`() =
        assertEquals(ConstraintResult.Allow, allowStrategicCommand().test(ctx(), view(n = nation(meta = linkedMapOf("war" to 0)))))
    @Test fun `allowStrategicCommand denies when nation war is non-zero`() =
        assertEquals("현재 전쟁 금지입니다.", deny(allowStrategicCommand().test(ctx(), view(n = nation(meta = linkedMapOf("war" to 1))))))

    // --- reqNationGold / reqNationRice ---
    @Test fun `reqNationGold allows when gold meets req`() =
        assertEquals(ConstraintResult.Allow, reqNationGold { _, _ -> 1000 }.test(ctx(), view(n = nation(gold = 1000))))
    @Test fun `reqNationGold denies when gold below req`() =
        assertEquals("국고가 부족합니다.", deny(reqNationGold { _, _ -> 1000 }.test(ctx(), view(n = nation(gold = 999)))))
    @Test fun `reqNationRice allows when rice meets req`() =
        assertEquals(ConstraintResult.Allow, reqNationRice { _, _ -> 1000 }.test(ctx(), view(n = nation(rice = 1000))))
    @Test fun `reqNationRice denies when rice below req`() =
        assertEquals("병량이 부족합니다.", deny(reqNationRice { _, _ -> 1000 }.test(ctx(), view(n = nation(rice = 999)))))

    // --- availableRecruitCrewType (preloaded predicate; PHP runs crewType.isValid) ---
    @Test fun `availableRecruitCrewType allows when recruitable`() =
        assertEquals(ConstraintResult.Allow, availableRecruitCrewType(crewTypeId = 1) { _, _ -> true }.test(ctx(), view()))
    @Test fun `availableRecruitCrewType denies when not recruitable`() =
        assertEquals("현재 선택할 수 없는 병종입니다.", deny(availableRecruitCrewType(crewTypeId = 1) { _, _ -> false }.test(ctx(), view())))

    // --- reqCityCapacity (numeric path; josa-이) ---
    @Test fun `reqCityCapacity allows when value meets numeric req`() =
        assertEquals(ConstraintResult.Allow, reqCityCapacity("comm", "상업", 100).test(ctx(), view(c = city())))
    @Test fun `reqCityCapacity denies with josa-i when value below req`() =
        assertEquals("상업이 부족합니다.", deny(reqCityCapacity("comm", "상업", 200).test(ctx(), view(c = city()))))

    // --- suppliedCity (kept from P1; supplyState truthy → allow) ---
    @Test fun `suppliedCity allows when supplied`() =
        assertEquals(ConstraintResult.Allow, suppliedCity().test(ctx(), view(c = city(supplyState = 1))))
    @Test fun `suppliedCity denies when isolated`() =
        assertEquals("고립된 도시입니다.", deny(suppliedCity().test(ctx(), view(c = city(supplyState = 0)))))

    // --- Unknown propagation ---
    @Test fun `beNeutral returns Unknown when general missing`() {
        val empty = MemoryStateView(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        assertTrue(beNeutral().test(ctx(), empty) is ConstraintResult.Unknown)
    }
}
