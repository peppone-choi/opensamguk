package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 시나리오 코드 → 표시 제목 해석 검증. 라이브 버그 3(로비가 제목 대신 시나리오 코드 표시) 수정.
 * 제목은 커밋된 scenario 리소스의 `title` 에서 read-time 해석(legacy getTitle 과 동일 출처).
 * 제품 기본 시나리오(`scenario_990002`)로 잰다 — 은퇴한 삼모 시나리오는 클래스패스를 떠난다(#917).
 */
class ScenarioTitleResolverTest {

    private val resolver = ScenarioTitleResolver()

    @Test
    fun `scenario_990002 코드를 리소스 title 로 해석한다`() {
        assertEquals("휘하 예주 조각 (합성 운영 후보)", resolver.titleOf("scenario_990002"))
    }

    @Test
    fun `두 번째 호출도 동일(캐시)`() {
        val first = resolver.titleOf("scenario_990002")
        assertEquals(first, resolver.titleOf("scenario_990002"))
    }

    @Test
    fun `미존재 코드는 null (컨트롤러가 코드로 폴백)`() {
        assertNull(resolver.titleOf("scenario_does_not_exist"))
    }

    @Test
    fun `빈 문자열·null 은 null`() {
        assertNull(resolver.titleOf(""))
        assertNull(resolver.titleOf(null))
    }
}
