package opensamguk.logic.actions.nation

import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintMode
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.City
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.CityConstRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * che_감축 constraint 팩 — `che_감축.php:63-71`. 누락됐던 **두 번째** `ReqDestCityValue`
 * (`level > origCityLevel`, origCityLevel = `CityConst::byID(capital)->level` 정적 레벨)를 핀(#14).
 *
 * 의미: 감축은 증축으로 정적 레벨 위로 키운 분만 되돌릴 수 있다 → 현재 level 이 origCityLevel
 * 이하면 '더이상 감축할 수 없습니다.' deny. no-rng / Docker 불요(제약 단위).
 */
class CheGamchukConstraintTest {

    private val pipeline = GeneralActionPipeline()

    // 실제 시나리오 도시(계양, id=30). origCityLevel = 그 도시의 CityConst 정적 레벨.
    private val capitalId = 30
    private val origLevel = CityConstRegistry.of("che").byId(capitalId)!!.level

    private fun ctx(cityId: Int = capitalId, mapName: String = "che") = ConstraintContext(
        actorId = 1, cityId = cityId, nationId = 1, destCityId = cityId,
        env = mapOf("mapName" to mapName),
        mode = ConstraintMode.FULL,
    )

    private fun viewWith(city: City) = object : StateView {
        override fun has(req: RequirementKey) = true
        override fun get(req: RequirementKey): Any? =
            if (req == RequirementKey.DestCity(capitalId)) city else null
    }

    // 제약은 level 만 읽는다 — 나머지 필수 필드는 더미.
    private fun cityAt(level: Int) = City(
        id = capitalId, nationId = 1, level = level,
        commerce = 0, commerceMax = 0, agriculture = 0, agricultureMax = 0,
        supplyState = 1, frontState = 0, trust = 0.0,
    )

    @Test
    fun `감축은 ReqDestCityValue 두 개를 갖는다 (level gt 4 + level gt origCityLevel)`() {
        val names = cheGamchuk(pipeline).buildConstraints(ctx()).map { it.name }
        assertEquals(5, names.size, "occupiedCity+beChief+suppliedCity+2 reqDestCityValue")
        assertEquals(2, names.count { it == "ReqDestCityValue" }, "두 번째 ReqDestCityValue 가 있어야")
    }

    @Test
    fun `현재 level 이 origCityLevel 이하면 두 번째 제약이 감축을 deny 한다`() {
        // buildConstraints 의 다섯 번째(idx 4) = level > origCityLevel.
        val second = cheGamchuk(pipeline).buildConstraints(ctx())[4]
        val res = second.test(ctx(), viewWith(cityAt(origLevel)))
        assertIs<ConstraintResult.Deny>(res)
        assertTrue(res.reason.contains("더이상 감축할 수 없습니다"), "deny 사유=PHP errMsg")
    }

    @Test
    fun `현재 level 이 origCityLevel 초과면 두 번째 제약을 통과한다`() {
        val second = cheGamchuk(pipeline).buildConstraints(ctx())[4]
        assertEquals(ConstraintResult.Allow, second.test(ctx(), viewWith(cityAt(origLevel + 1))))
    }

    @Test
    fun `Han capital reduction floor comes from the Han catalog`() {
        val hanContext = ctx(cityId = 421, mapName = "han")
        val destination = cityAt(10).copy(id = 421)
        val view = object : StateView {
            override fun has(req: RequirementKey) = true
            override fun get(req: RequirementKey): Any? =
                if (req == RequirementKey.DestCity(421)) destination else null
        }
        val result = cheGamchuk(pipeline).buildConstraints(hanContext)[4].test(hanContext, view)

        assertIs<ConstraintResult.Deny>(result)
    }
}
