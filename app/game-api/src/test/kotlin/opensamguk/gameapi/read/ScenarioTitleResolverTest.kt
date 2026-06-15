package opensamguk.gameapi.read

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 시나리오 코드 → 표시 제목 해석 검증. 라이브 버그 3(로비가 코드 `scenario_1010` 표시) 수정.
 * 제목은 커밋된 scenario 리소스의 `title` 에서 read-time 해석(legacy getTitle 과 동일 출처).
 */
class ScenarioTitleResolverTest {

    private val resolver = ScenarioTitleResolver()

    @Test
    fun `scenario_1010 코드를 리소스 title 로 해석한다`() {
        assertEquals("【역사모드1】 황건적의 난", resolver.titleOf("scenario_1010"))
    }

    @Test
    fun `두 번째 호출도 동일(캐시)`() {
        val first = resolver.titleOf("scenario_1010")
        assertEquals(first, resolver.titleOf("scenario_1010"))
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
