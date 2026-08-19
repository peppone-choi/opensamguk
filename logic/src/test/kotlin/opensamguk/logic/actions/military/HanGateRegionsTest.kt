package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.HanGateIndex
import opensamguk.common.constants.UnitCatalog
import opensamguk.common.constants.UnitConstraint
import opensamguk.logic.domain.General
import opensamguk.logic.world.CityConstRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * han 병종의 게이트 키 판정 — [HanGateIndex] (생성물) 가 실제로 물리는지 고정한다.
 *
 * 규칙(확정): `ReqRegions` = 국가가 **보유한 城** 중 하나라도 그 키를 가지면 통과(인접은 보지 않는다).
 * `ForbidRegions` = 장수가 **서 있는 城**의 키로 판정한다.
 */
class HanGateRegionsTest {

    private val han = CityConstRegistry.of("han")
    private val che = CityConstRegistry.of("che")

    // 城 id 는 생성기가 州 → 郡 순으로 다시 매기면 통째로 밀린다. 박아두지 말고 게이트 키로 찾는다.
    private fun cityWithKey(key: String) = han.all().keys.first { key in HanGateIndex.keys(it) }

    /** 幽州 게이트 키를 가진 城. */
    private val youzhouCity = cityWithKey("幽州")

    /** 烏桓 거점. 幽州 城들과 인접하지만 州 밖(외부)이다. */
    private val wuhuanCity = cityWithKey("烏桓")

    /** 邪馬壹國(왜). 기병 32종의 ForbidRegions 대상이다. */
    private val waCity = cityWithKey("邪馬壹國")

    /** 京兆尹(治 장안) — 司隸. 幽州·烏桓·鮮卑 어느 키도 없다.
     *  '장안'은 會稽郡에도 있어 이름만으로는 못 찍는다 — 州까지 같이 본다. */
    private val changanCity = han.all().values
        .first { it.name == "장안" && it.region == han.regionIdByName("사예") }.id

    private fun general(cityId: Int) = General(
        id = 1, nationId = 1, cityId = cityId,
        leadership = 70, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 0, rice = 0, crew = 0, train = 0.0, atmos = 0.0, crewTypeId = 2000,
    )

    private fun canRecruit(
        unit: opensamguk.common.constants.GameUnitDetail,
        standingAt: Int,
        own: List<Int>,
        cityConst: opensamguk.logic.world.CityConstVariant = han,
    ): Boolean = RecruitUnitAvailability.isValid(
        unit, general(standingAt),
        ownCities = own.associateWith { 5 },
        ownRegions = own.mapNotNull { cityConst.byId(it)?.region }.toSet(),
        relYear = 100, tech = 99999, nationAux = emptyMap(), cityConst = cityConst,
    )

    /** 유주돌기 — ReqTech 2000 + ReqRegions(幽州·烏桓·鮮卑) + 왜/마한 ForbidRegions. */
    private val youzhouTuqi = UnitCatalog.byId("han", 2100)!!

    @Test
    fun `幽州 城을 보유하면 유주돌기를 뽑는다`() {
        assertTrue(canRecruit(youzhouTuqi, standingAt = youzhouCity, own = listOf(youzhouCity)))
    }

    @Test
    fun `幽州 烏桓 鮮卑 어느 것도 없으면 유주돌기를 못 뽑는다`() {
        assertFalse(canRecruit(youzhouTuqi, standingAt = changanCity, own = listOf(changanCity)))
    }

    @Test
    fun `烏桓 거점은 보유해야 통과하고 인접만으로는 안 된다`() {
        val wuhuanOnly = youzhouTuqi.copy(reqConstraints = listOf(UnitConstraint.ReqRegions(listOf("烏桓"))))
        val neighbour = han.byId(wuhuanCity)!!.path.keys.first { "幽州" in HanGateIndex.keys(it) }

        assertTrue(canRecruit(wuhuanOnly, standingAt = wuhuanCity, own = listOf(wuhuanCity)))
        assertFalse(canRecruit(wuhuanOnly, standingAt = neighbour, own = listOf(neighbour)))
    }

    @Test
    fun `왜에 주둔한 장수는 금제 기병을 못 뽑는다`() {
        val forbidden = UnitCatalog.all("han").values.filter { u ->
            u.reqConstraints.any { it is UnitConstraint.ForbidRegions && "邪馬壹國" in it.forbidRegions }
        }
        assertEquals(32, forbidden.size, "금제 기병 32종")

        // 幽州·烏桓 을 다 보유해 ReqRegions 는 모두 통과시키고, 주둔지만 왜로 둔다.
        val own = listOf(youzhouCity, wuhuanCity, waCity)
        assertTrue(canRecruit(youzhouTuqi, standingAt = youzhouCity, own = own), "주둔지가 유주면 통과")
        for (unit in forbidden) {
            assertFalse(canRecruit(unit, standingAt = waCity, own = own), "왜 주둔: ${unit.name}")
        }
    }

    @Test
    fun `che 는 게이트 키가 없어 기존 지역 라벨 경로 그대로다`() {
        assertTrue(che.all().keys.all { che.gateKeys(it).isEmpty() })

        val unit = GameUnitConst.byId(1300)!!.copy(reqConstraints = listOf(UnitConstraint.ReqRegions(listOf("하북"))))
        val hebei = che.all().values.first { it.region == che.regionIdByName("하북") }.id
        val other = che.all().values.first { it.region != che.regionIdByName("하북") }.id
        assertTrue(canRecruit(unit, standingAt = hebei, own = listOf(hebei), cityConst = che))
        assertFalse(canRecruit(unit, standingAt = other, own = listOf(other), cityConst = che))
    }
}
