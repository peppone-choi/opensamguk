package opensamguk.logic.actions.military

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.UnitConstraint
import opensamguk.logic.domain.General
import opensamguk.logic.world.CityConstRegistry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * v2 확장 제약 [UnitConstraint.ForbidRegions] — 주둔지 기준 금제.
 * 소유(ReqRegions)가 아니라 지금 서 있는 땅으로 판정하는지만 고정한다.
 */
class ForbidRegionsTest {

    private val cityConst = CityConstRegistry.of("che")
    private val cityId = 7
    private val here = cityConst.byId(cityId)!!.region
    private val hereName = cityConst.all().values.first { it.region == here }
        .let { c -> REGION_NAMES.first { cityConst.regionIdByName(it) == here } }

    private fun general() = General(
        id = 1, nationId = 1, cityId = cityId,
        leadership = 70, strength = 70, intel = 70, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 0, rice = 0, crew = 0, train = 0.0, atmos = 0.0, crewTypeId = 1100,
    )

    private fun canRecruit(forbid: List<String>): Boolean {
        val unit = GameUnitConst.byId(1300)!!.copy(reqConstraints = listOf(UnitConstraint.ForbidRegions(forbid)))
        return RecruitUnitAvailability.isValid(
            unit, general(), ownCities = mapOf(cityId to 5), ownRegions = setOf(here),
            relYear = 10, tech = 9999, nationAux = emptyMap(), cityConst = cityConst,
        )
    }

    @Test
    fun `주둔지가 금제 지역이면 뽑을 수 없다`() {
        assertFalse(canRecruit(listOf(hereName)))
    }

    @Test
    fun `다른 지역만 금제면 뽑을 수 있다 — 이름을 못 찾아도 막지 않는다`() {
        val elsewhere = REGION_NAMES.first { cityConst.regionIdByName(it) != here }
        assertTrue(canRecruit(listOf(elsewhere)))
        assertTrue(canRecruit(listOf("없는지역이름")))
    }

    private companion object {
        // che regionMap 의 이름들. 하나라도 이 목록에 없으면 위 필터가 터진다 — 그게 검사다.
        val REGION_NAMES = listOf("하북", "중원", "서북", "서촉", "초", "오월", "남중", "동이")
    }
}
