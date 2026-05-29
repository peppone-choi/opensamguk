package opensamguk.logic.constraints

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresetsTest {

    private fun general(
        id: Int = 1,
        nationId: Int = 1,
        cityId: Int = 5,
        gold: Int = 1000,
        rice: Int = 1000,
    ) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = gold, rice = rice,
    )

    private fun city(
        id: Int = 5,
        nationId: Int = 1,
        commerce: Int = 100,
        commerceMax: Int = 1000,
        agriculture: Int = 100,
        agricultureMax: Int = 1000,
        supplyState: Int = 1,
    ) = City(
        id = id, nationId = nationId, level = 5,
        commerce = commerce, commerceMax = commerceMax,
        agriculture = agriculture, agricultureMax = agricultureMax,
        supplyState = supplyState, frontState = 0, trust = 50.0,
    )

    private fun view(
        g: General = general(),
        c: City = city(),
        n: Nation = Nation(id = 1, level = 7, capitalCityId = 5),
    ) = MemoryStateView(
        generals = mapOf(g.id to g),
        cities = mapOf(c.id to c),
        nations = mapOf(n.id to n),
        env = emptyMap(),
    )

    private fun ctx(
        actorId: Int = 1,
        cityId: Int? = 5,
        nationId: Int? = 1,
    ) = ConstraintContext(actorId = actorId, cityId = cityId, nationId = nationId, mode = ConstraintMode.FULL)

    // --- notBeNeutral ---

    @Test
    fun `notBeNeutral allows when general has a nation`() {
        assertEquals(ConstraintResult.Allow, notBeNeutral().test(ctx(), view(g = general(nationId = 1))))
    }

    @Test
    fun `notBeNeutral denies when nationId is zero`() {
        val r = notBeNeutral().test(ctx(), view(g = general(nationId = 0)))
        assertEquals(ConstraintResult.Deny("재야입니다."), r)
    }

    // --- notWanderingNation ---

    @Test
    fun `notWanderingNation allows when nation level is non-zero`() {
        assertEquals(
            ConstraintResult.Allow,
            notWanderingNation().test(ctx(), view(n = Nation(id = 1, level = 7, capitalCityId = 5))),
        )
    }

    @Test
    fun `notWanderingNation denies when nation level is zero`() {
        val r = notWanderingNation().test(ctx(), view(n = Nation(id = 1, level = 0, capitalCityId = 5)))
        assertEquals(ConstraintResult.Deny("방랑군은 불가능합니다."), r)
    }

    // --- occupiedCity ---

    @Test
    fun `occupiedCity allows when city nation matches general nation`() {
        assertEquals(
            ConstraintResult.Allow,
            occupiedCity().test(ctx(), view(g = general(nationId = 1), c = city(nationId = 1))),
        )
    }

    @Test
    fun `occupiedCity denies when city belongs to another nation`() {
        val r = occupiedCity().test(ctx(), view(g = general(nationId = 1), c = city(nationId = 2)))
        assertEquals(ConstraintResult.Deny("아국이 아닙니다."), r)
    }

    // --- suppliedCity ---

    @Test
    fun `suppliedCity allows when supply state is non-zero`() {
        assertEquals(ConstraintResult.Allow, suppliedCity().test(ctx(), view(c = city(supplyState = 1))))
    }

    @Test
    fun `suppliedCity denies when supply state is zero`() {
        val r = suppliedCity().test(ctx(), view(c = city(supplyState = 0)))
        assertEquals(ConstraintResult.Deny("고립된 도시입니다."), r)
    }

    // --- reqGeneralGold ---

    @Test
    fun `reqGeneralGold allows when gold meets cost`() {
        val cst = reqGeneralGold { _, _ -> 100 }
        assertEquals(ConstraintResult.Allow, cst.test(ctx(), view(g = general(gold = 100))))
    }

    @Test
    fun `reqGeneralGold denies when gold is below cost`() {
        val cst = reqGeneralGold { _, _ -> 100 }
        val r = cst.test(ctx(), view(g = general(gold = 99)))
        assertEquals(ConstraintResult.Deny("자금이 모자랍니다."), r)
    }

    // --- reqGeneralRice ---

    @Test
    fun `reqGeneralRice allows when rice meets cost`() {
        val cst = reqGeneralRice { _, _ -> 100 }
        assertEquals(ConstraintResult.Allow, cst.test(ctx(), view(g = general(rice = 100))))
    }

    @Test
    fun `reqGeneralRice denies when rice is below cost`() {
        val cst = reqGeneralRice { _, _ -> 100 }
        val r = cst.test(ctx(), view(g = general(rice = 99)))
        assertEquals(ConstraintResult.Deny("군량이 모자랍니다."), r)
    }

    // --- remainCityCapacity ---

    @Test
    fun `remainCityCapacity allows when current is below max`() {
        val cst = remainCityCapacity("comm", "상업 투자")
        assertEquals(
            ConstraintResult.Allow,
            cst.test(ctx(), view(c = city(commerce = 100, commerceMax = 1000))),
        )
    }

    @Test
    fun `remainCityCapacity comm deny uses neun josa for 상업 투자`() {
        val cst = remainCityCapacity("comm", "상업 투자")
        val r = cst.test(ctx(), view(c = city(commerce = 1000, commerceMax = 1000)))
        // 투자 ends in vowel ㅏ → no jongsung → 는
        assertEquals(ConstraintResult.Deny("상업 투자는 충분합니다."), r)
    }

    @Test
    fun `remainCityCapacity agri deny uses eun josa for 농지 개간`() {
        val cst = remainCityCapacity("agri", "농지 개간")
        val r = cst.test(ctx(), view(c = city(agriculture = 1000, agricultureMax = 1000)))
        // 개간 ends in ㄴ jongsung → 은
        assertEquals(ConstraintResult.Deny("농지 개간은 충분합니다."), r)
    }

    @Test
    fun `remainCityCapacity agri allows when agriculture is below max`() {
        val cst = remainCityCapacity("agri", "농지 개간")
        assertEquals(
            ConstraintResult.Allow,
            cst.test(ctx(), view(c = city(agriculture = 100, agricultureMax = 1000))),
        )
    }

    // --- Unknown propagation (missing entity → Unknown) ---

    @Test
    fun `notBeNeutral returns Unknown when general missing`() {
        val emptyView = MemoryStateView(
            generals = emptyMap(), cities = emptyMap(), nations = emptyMap(), env = emptyMap(),
        )
        val r = notBeNeutral().test(ctx(), emptyView)
        assertTrue(r is ConstraintResult.Unknown)
    }
}
