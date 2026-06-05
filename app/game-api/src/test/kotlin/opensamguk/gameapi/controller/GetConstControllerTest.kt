package opensamguk.gameapi.controller

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.GameConst
import opensamguk.common.constants.GameUnitConst
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * W3 — [GetConstController] 슬라이스 테스트. DB/레포 주입이 전혀 없는 정적 상수 서비스이므로
 * MockMvc standalone만으로 검증한다(PHP `GetConst`는 NO_SESSION·DB-free 정적 API).
 *
 * 검증 포인트:
 *  - gameUnitConst가 `GameUnitConst.all()`의 모든 병종을 노출(첫 행 = 성벽 id 1000).
 *  - cityConst가 `CityConst.all()`의 94개 도시를 노출(id 1 = 업).
 *  - cityConstMap.region/level이 라벨→int 한 방향으로 노출(하북→1, 수→1).
 *  - iAction 키맵이 nationType/crewtype 등을 value-only로 노출, name은 BLOCKED(null/부재).
 *  - gameConst 번들에 표시 상수가 담김(maxTurn 등).
 */
class GetConstControllerTest {

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(GetConstController()).build()

    @Test
    fun `const bundle exposes unit, city, cityConstMap, iAction, gameConst`() {
        mockMvc().perform(get("/api/const"))
            .andExpect(status().isOk)
            // gameUnitConst: 전체 병종 수 = GameUnitConst.all().size, 첫 행은 성벽(id 1000).
            .andExpect(jsonPath("$.gameUnitConst.length()").value(GameUnitConst.all().size))
            .andExpect(jsonPath("$.gameUnitConst[0].id").value(1000))
            .andExpect(jsonPath("$.gameUnitConst[0].name").value("성벽"))
            // cityConst: 94개 도시(che 정본), 첫 행은 id 1 업.
            .andExpect(jsonPath("$.cityConst.length()").value(CityConst.all().size))
            .andExpect(jsonPath("$.cityConst[0].id").value(1))
            .andExpect(jsonPath("$.cityConst[0].name").value("업"))
            // cityConstMap: 라벨→int 한 방향.
            .andExpect(jsonPath("$.cityConstMap.region.하북").value(1))
            .andExpect(jsonPath("$.cityConstMap.level.수").value(1))
            .andExpect(jsonPath("$.cityConstMap.level.소").value(5)) // lv5='소'(프로젝트 메모리)
            // iAction: nationType value-only(name은 BLOCKED → null).
            .andExpect(jsonPath("$.iAction.nationType[0].value").value(GameConst.availableNationType[0]))
            .andExpect(jsonPath("$.iAction.nationType[0].name").value(nullValue())) // BLOCKED → null

            .andExpect(jsonPath("$.iAction.crewtype[0].value").value("1000")) // 첫 병종 id 문자열
            // gameConst 번들 표시 상수.
            .andExpect(jsonPath("$.gameConst.maxTurn").value(GameConst.maxTurn))
            .andExpect(jsonPath("$.gameConst.mapName").value(GameConst.mapName))
    }

    @Test
    fun `nationType bundle includes neutral type at the tail`() {
        mockMvc().perform(get("/api/const"))
            .andExpect(status().isOk)
            // available + neutral 길이.
            .andExpect(
                jsonPath("$.iAction.nationType.length()")
                    .value(GameConst.availableNationType.size + 1),
            )
            // 마지막 항목 = 중립 타입.
            .andExpect(
                jsonPath("$.iAction.nationType[${GameConst.availableNationType.size}].value")
                    .value(GameConst.neutralNationType),
            )
    }
}
