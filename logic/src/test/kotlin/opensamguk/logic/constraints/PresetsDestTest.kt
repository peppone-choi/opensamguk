package opensamguk.logic.constraints

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CD2 — byte-for-byte reason strings for the dest-* / diplomacy / name-dup presets.
 *
 * Port oracle = PHP Constraint/{FriendlyDestGeneral,ExistsDestGeneral,DifferentDestNation,
 * DifferentNationDestGeneral,NotNeutralDestCity,NotSameDestCity,NotOccupiedDestCity,OccupiedDestCity,
 * SuppliedDestCity,NearCity,CheckNationNameDuplicate,AllowJoinDestNation,AllowDiplomacyStatus,
 * ReqDestCityValue,ReqDestNationValue}.php — reason strings + test order. The DB/pathfinding-backed
 * subset takes a preloaded StateView key / predicate (NO DB or searchDistance inside test()).
 */
class PresetsDestTest {

    private fun general(
        id: Int = 1,
        nationId: Int = 1,
        cityId: Int = 5,
        npcType: Int = 0,
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100, rice = 100, npcType = npcType,
    )

    private fun city(
        id: Int = 7,
        nationId: Int = 2,
        supplyState: Int = 1,
        comm: Int = 100, commMax: Int = 1000,
    ) = City(
        id = id, nationId = nationId, level = 5,
        commerce = comm, commerceMax = commMax,
        agriculture = 1, agricultureMax = 10,
        supplyState = supplyState, frontState = 0, trust = 50.0,
    )

    private fun nation(
        id: Int = 2,
        name: String = "촉",
        gennum: Int = 0,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = Nation(id = id, level = 7, capitalCityId = null, name = name, gennum = gennum, meta = meta)

    private fun ctx(
        actorId: Int = 1,
        cityId: Int? = 5,
        nationId: Int? = 1,
        destGeneralId: Int? = null,
        destCityId: Int? = null,
        destNationId: Int? = null,
        args: Map<String, Any?> = emptyMap(),
    ) = ConstraintContext(
        actorId = actorId, cityId = cityId, nationId = nationId,
        destGeneralId = destGeneralId, destCityId = destCityId, destNationId = destNationId,
        args = args, mode = ConstraintMode.FULL,
    )

    private fun view(
        generals: List<General> = emptyList(),
        cities: List<City> = emptyList(),
        nations: List<Nation> = emptyList(),
        diplomacy: List<Diplomacy> = emptyList(),
    ) = MemoryStateView(
        generals = generals.associateBy { it.id },
        cities = cities.associateBy { it.id },
        nations = nations.associateBy { it.id },
        env = emptyMap(),
        diplomacy = diplomacy,
    )

    private fun deny(r: ConstraintResult): String {
        assertTrue(r is ConstraintResult.Deny, "expected Deny but was $r")
        return (r as ConstraintResult.Deny).reason
    }

    private fun assertAllow(r: ConstraintResult) =
        assertTrue(r is ConstraintResult.Allow, "expected Allow but was $r")

    // --- FriendlyDestGeneral ---
    @Test fun `friendlyDestGeneral allows when same nation`() {
        val actor = general(1, nationId = 1)
        val dest = general(2, nationId = 1)
        assertAllow(friendlyDestGeneral().test(ctx(destGeneralId = 2), view(generals = listOf(actor, dest))))
    }
    @Test fun `friendlyDestGeneral denies when different nation`() {
        val actor = general(1, nationId = 1)
        val dest = general(2, nationId = 2)
        assertEquals("아국 장수가 아닙니다.", deny(friendlyDestGeneral().test(ctx(destGeneralId = 2), view(generals = listOf(actor, dest)))))
    }

    // --- ExistsDestGeneral (pass when dest id truthy; id==0 → none) ---
    @Test fun `existsDestGeneral allows when dest id truthy`() {
        val actor = general(1)
        val dest = general(2, nationId = 2)
        assertAllow(existsDestGeneral().test(ctx(destGeneralId = 2), view(generals = listOf(actor, dest))))
    }
    @Test fun `existsDestGeneral denies when dest id is zero`() {
        val actor = general(1)
        val dest = general(0, nationId = 0)
        assertEquals("없는 장수입니다.", deny(existsDestGeneral().test(ctx(destGeneralId = 0), view(generals = listOf(actor, dest)))))
    }

    // --- DifferentDestNation (pass when destNation.id != actor.nation) ---
    @Test fun `differentDestNation allows when different`() {
        val actor = general(1, nationId = 1)
        val destN = nation(2)
        assertAllow(differentDestNation().test(ctx(destNationId = 2), view(generals = listOf(actor), nations = listOf(destN))))
    }
    @Test fun `differentDestNation denies when same`() {
        val actor = general(1, nationId = 2)
        val destN = nation(2)
        assertEquals("이미 같은 국가입니다.", deny(differentDestNation().test(ctx(destNationId = 2), view(generals = listOf(actor), nations = listOf(destN)))))
    }

    // --- DifferentNationDestGeneral ---
    @Test fun `differentNationDestGeneral allows when different`() {
        val actor = general(1, nationId = 1)
        val dest = general(2, nationId = 2)
        assertAllow(differentNationDestGeneral().test(ctx(destGeneralId = 2), view(generals = listOf(actor, dest))))
    }
    @Test fun `differentNationDestGeneral denies when same nation`() {
        val actor = general(1, nationId = 1)
        val dest = general(2, nationId = 1)
        assertEquals("같은 국가의 장수입니다.", deny(differentNationDestGeneral().test(ctx(destGeneralId = 2), view(generals = listOf(actor, dest)))))
    }

    // --- NotNeutralDestCity (pass when destCity.nation != 0) ---
    @Test fun `notNeutralDestCity allows when occupied`() {
        val c = city(7, nationId = 2)
        assertAllow(notNeutralDestCity().test(ctx(destCityId = 7), view(cities = listOf(c))))
    }
    @Test fun `notNeutralDestCity denies when neutral`() {
        val c = city(7, nationId = 0)
        assertEquals("공백지입니다.", deny(notNeutralDestCity().test(ctx(destCityId = 7), view(cities = listOf(c)))))
    }

    // --- NotSameDestCity (pass when destCity.id != actor.city) ---
    @Test fun `notSameDestCity allows when different city`() {
        val actor = general(1, cityId = 5)
        val c = city(7)
        assertAllow(notSameDestCity().test(ctx(cityId = 5, destCityId = 7), view(generals = listOf(actor), cities = listOf(c))))
    }
    @Test fun `notSameDestCity denies when same city`() {
        val actor = general(1, cityId = 5)
        val c = city(5)
        assertEquals("같은 도시입니다.", deny(notSameDestCity().test(ctx(cityId = 5, destCityId = 5), view(generals = listOf(actor), cities = listOf(c)))))
    }

    // --- NotOccupiedDestCity (pass when destCity.nation != actor.nation) ---
    @Test fun `notOccupiedDestCity allows when not mine`() {
        val actor = general(1, nationId = 1)
        val c = city(7, nationId = 2)
        assertAllow(notOccupiedDestCity().test(ctx(destCityId = 7), view(generals = listOf(actor), cities = listOf(c))))
    }
    @Test fun `notOccupiedDestCity denies when mine`() {
        val actor = general(1, nationId = 1)
        val c = city(7, nationId = 1)
        assertEquals("아국입니다.", deny(notOccupiedDestCity().test(ctx(destCityId = 7), view(generals = listOf(actor), cities = listOf(c)))))
    }

    // --- OccupiedDestCity (pass when destCity.nation == actor.nation) ---
    @Test fun `occupiedDestCity allows when mine`() {
        val actor = general(1, nationId = 1)
        val c = city(7, nationId = 1)
        assertAllow(occupiedDestCity().test(ctx(destCityId = 7), view(generals = listOf(actor), cities = listOf(c))))
    }
    @Test fun `occupiedDestCity denies when not mine`() {
        val actor = general(1, nationId = 1)
        val c = city(7, nationId = 2)
        assertEquals("대상 도시가 아국이 아닙니다.", deny(occupiedDestCity().test(ctx(destCityId = 7), view(generals = listOf(actor), cities = listOf(c)))))
    }

    // --- SuppliedDestCity (pass when destCity.supply truthy) ---
    @Test fun `suppliedDestCity allows when supplied`() {
        val c = city(7, supplyState = 1)
        assertAllow(suppliedDestCity().test(ctx(destCityId = 7), view(cities = listOf(c))))
    }
    @Test fun `suppliedDestCity denies when isolated`() {
        val c = city(7, supplyState = 0)
        assertEquals("고립된 도시입니다.", deny(suppliedDestCity().test(ctx(destCityId = 7), view(cities = listOf(c)))))
    }

    // --- NearCity (preloaded reachable-set; arg==1 vs arg>1 reason fork) ---
    @Test fun `nearCity allows when dest reachable`() {
        val actor = general(1, cityId = 5)
        val c = city(7)
        val cons = nearCity(1) { _, _ -> setOf(6, 7, 8) }
        assertAllow(cons.test(ctx(cityId = 5, destCityId = 7), view(generals = listOf(actor), cities = listOf(c))))
    }
    @Test fun `nearCity denies adjacent when arg is 1`() {
        val actor = general(1, cityId = 5)
        val c = city(99)
        val cons = nearCity(1) { _, _ -> setOf(6, 7, 8) }
        assertEquals("인접도시가 아닙니다.", deny(cons.test(ctx(cityId = 5, destCityId = 99), view(generals = listOf(actor), cities = listOf(c)))))
    }
    @Test fun `nearCity denies distance when arg over 1`() {
        val actor = general(1, cityId = 5)
        val c = city(99)
        val cons = nearCity(3) { _, _ -> setOf(6, 7, 8) }
        assertEquals("거리가 너무 멉니다.", deny(cons.test(ctx(cityId = 5, destCityId = 99), view(generals = listOf(actor), cities = listOf(c)))))
    }

    // --- CheckNationNameDuplicate (scans NationList; excludes self) ---
    @Test fun `checkNationNameDuplicate allows when name free`() {
        val n1 = nation(1, name = "위")
        val cons = checkNationNameDuplicate()
        assertAllow(cons.test(ctx(nationId = 1, args = mapOf("name" to "신생국")), view(nations = listOf(n1))))
    }
    @Test fun `checkNationNameDuplicate denies when name taken by another`() {
        val n1 = nation(1, name = "위")
        val n2 = nation(2, name = "촉")
        val cons = checkNationNameDuplicate()
        assertEquals("존재하는 국가명입니다.", deny(cons.test(ctx(nationId = 1, args = mapOf("name" to "촉")), view(nations = listOf(n1, n2)))))
    }
    @Test fun `checkNationNameDuplicate allows own name (self excluded)`() {
        val n1 = nation(1, name = "위")
        val cons = checkNationNameDuplicate()
        assertAllow(cons.test(ctx(nationId = 1, args = mapOf("name" to "위")), view(nations = listOf(n1))))
    }

    // --- AllowJoinDestNation (4-branch order) ---
    private fun joinView(destName: String = "촉", gennum: Int = 0, scout: Int = 0): MemoryStateView {
        val actor = general(1, nationId = 0)
        val destN = nation(2, name = destName, gennum = gennum, meta = linkedMapOf("scout" to scout))
        return view(generals = listOf(actor), nations = listOf(destN))
    }
    @Test fun `allowJoinDestNation branch1 opening-part gennum limit`() {
        val v = joinView(gennum = 10)
        assertEquals("임관이 제한되고 있습니다.", deny(allowJoinDestNation { _, _ -> 2 }.test(ctx(destNationId = 2, args = mapOf("relYear" to 0)), v)))
    }
    @Test fun `allowJoinDestNation branch2 scout banned`() {
        val v = joinView(scout = 1)
        assertEquals("임관이 금지되어 있습니다.", deny(allowJoinDestNation { _, _ -> 2 }.test(ctx(destNationId = 2, args = mapOf("relYear" to 5)), v)))
    }
    @Test fun `allowJoinDestNation branch3 user cannot join 태수국`() {
        // npc < 2 && name starts with ⓤ
        val v = joinView(destName = "ⓤ촉")
        assertEquals("유저장은 태수국에 임관할 수 없습니다.", deny(allowJoinDestNation { _, _ -> 0 }.test(ctx(destNationId = 2, args = mapOf("relYear" to 5)), v)))
    }
    @Test fun `allowJoinDestNation branch4 non-foreign cannot join 이민족`() {
        // npc != 9 && name starts with ⓞ
        val v = joinView(destName = "ⓞ촉")
        assertEquals("이민족 국가에 임관할 수 없습니다.", deny(allowJoinDestNation { _, _ -> 2 }.test(ctx(destNationId = 2, args = mapOf("relYear" to 5)), v)))
    }
    @Test fun `allowJoinDestNation allows in normal case`() {
        val v = joinView()
        assertAllow(allowJoinDestNation { _, _ -> 2 }.test(ctx(destNationId = 2, args = mapOf("relYear" to 5)), v))
    }
    @Test fun `allowJoinDestNation branch order branch1 wins over scout`() {
        // both branch1 (opening gennum) and branch2 (scout) hold → branch1 reason
        val v = joinView(gennum = 10, scout = 1)
        assertEquals("임관이 제한되고 있습니다.", deny(allowJoinDestNation { _, _ -> 2 }.test(ctx(destNationId = 2, args = mapOf("relYear" to 0)), v)))
    }

    // --- AllowDiplomacyStatus (preloaded directional diplomacy existence predicate) ---
    // The predicate scans the preloaded directional rows for `me=nationID && state IN allowStatus`,
    // proving the CD1 Diplomacy preload path drives the constraint (no DB inside test()).
    private fun diplomacyMatch(nationId: Int, allowStatus: List<Int>): (ConstraintContext, StateView) -> Boolean =
        { _, v ->
            // scan the directional rows the view holds for this `me` (any `you`) with a matching state.
            (1..50).any { you ->
                val row = v.get(RequirementKey.Diplomacy(nationId, you)) as? Diplomacy
                row != null && row.state in allowStatus
            }
        }
    @Test fun `allowDiplomacyStatus allows when state in allowSet`() {
        val dip = Diplomacy(me = 1, you = 2, state = 0)
        val cons = allowDiplomacyStatus("전쟁 상태가 아닙니다.", diplomacyMatch(1, listOf(0, 1)))
        assertAllow(cons.test(ctx(nationId = 1), view(diplomacy = listOf(dip))))
    }
    @Test fun `allowDiplomacyStatus denies when no matching state`() {
        val dip = Diplomacy(me = 1, you = 2, state = 7)
        val cons = allowDiplomacyStatus("전쟁 상태가 아닙니다.", diplomacyMatch(1, listOf(0, 1)))
        assertEquals("전쟁 상태가 아닙니다.", deny(cons.test(ctx(nationId = 1), view(diplomacy = listOf(dip)))))
    }

    // --- reqDestCityValue / reqDestNationValue (key-backed, comparator core shared with ReqCityValue) ---
    @Test fun `reqDestCityValue allows when comparator passes`() {
        val c = city(7, comm = 500, commMax = 1000)
        assertAllow(reqDestCityValue("comm", "상업", ">=", 100).test(ctx(destCityId = 7), view(cities = listOf(c))))
    }
    @Test fun `reqDestCityValue denies with derived reason`() {
        val c = city(7, comm = 50, commMax = 1000)
        assertEquals("상업이 부족합니다.", deny(reqDestCityValue("comm", "상업", ">=", 100).test(ctx(destCityId = 7), view(cities = listOf(c)))))
    }
    @Test fun `reqDestNationValue allows when comparator passes`() {
        val n = nation(2, gennum = 5)
        assertAllow(reqDestNationValue("gennum", "장수 수", ">=", 3).test(ctx(destNationId = 2), view(nations = listOf(n))))
    }
    @Test fun `reqDestNationValue denies with errMsg override`() {
        val n = nation(2, gennum = 1)
        assertEquals("장수가 부족합니다.", deny(reqDestNationValue("gennum", "장수 수", ">=", 3, errMsg = "장수가 부족합니다.").test(ctx(destNationId = 2), view(nations = listOf(n)))))
    }
}
