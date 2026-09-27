package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 기본 지도 郡 병합/재번호매김 캐너리 — 초록을 위한 테이블이 아니다.
 *
 * 스위트 전역에 걸쳐 여러 테스트가 기본 지도 城 id 를 하드코딩한다(예: `421` 을 「3 과 인접한
 * 기본 지도 城」이라는 뜻으로 씀). 재번호매김이 일어나면 그 id 가 **다른 城을 가리키게
 * 되어도 그 테스트가 실제로 검사하는 조건(인접·소속 등)만 우연히 계속 성립**하면 그
 * 테스트들은 계속 초록을 낸다 — 무엇을
 * 검사하는지 모른 채로. 실제로 이번 병합에서 421 이 「석」에서 「무당」으로 바뀌었는데
 * `ConquerCityResetTest` 등은 421 이 3 과 인접하다는 사실만 보므로 여전히 초록이었다.
 *
 * 이 테이블이 빨개지면 — **값을 여기 맞추지 마라.** 대신 흩어진 하드코딩 지점을 전부
 * 찾아(`grep -rn "\b<옛id>\b"`) 그 id 가 여전히 원래 검사하려던 城을 가리키는지 하나하나
 * 확인해라. 이 테스트가 하는 일은 딱 하나 — 「id 가 조용히 딴 城이 됐다」를 첫 신호로
 * 드러내는 것이다.
 */
class BaselineCityIdCanaryTest {

    @Test
    fun `기본 지도 city id 들의 정체가 그대로다`() {
        val map = CityConstRegistry.of("han")

        assertEquals("상", map.byId(3)!!.name)
        assertEquals("민지", map.byId(29)!!.name)

        assertEquals("석", map.byId(419)!!.name)
        assertEquals(map.regionIdByName("형주"), map.byId(419)!!.region, "419 = 남양군/형주 「석」(析)")

        assertEquals("석", map.byId(595)!!.name)
        assertEquals(map.regionIdByName("익주"), map.byId(595)!!.region, "595 = 한중군/익주 「석」(锡)")

        assertEquals("무당", map.byId(421)!!.name, "421 은 더 이상 「석」이 아니다 — 남양군/형주 「무당」")
    }
}
