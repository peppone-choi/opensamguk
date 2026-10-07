package opensamguk.engine.boot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 리셋 옵션이 게임 월드에 도달하는지 고정한다.
 *
 * 배경: 어드민 리셋은 16개 옵션을 보내지만 예전에는 `scenarioCode`만 월드에 닿았고
 * 턴 주기는 항상 상수 60으로 시드됐다. 운영자가 고른 값이 조용히 버려진 것이다.
 *
 * 여기서는 도메인 우선순위와 허용 집합을 고정한다.
 * pep 전용 60분 옵션은 tools/ops/test_pep_loop.py에서 별도로 검증한다.
 */
class SeedBootstrapTurnTermTest {

    private fun resolve(qa: String? = null, reset: String? = null): Int =
        SeedBootstrap.resolveTurnTerm(qa, reset)

    @Test
    fun `둘 다 없으면 기본 60분이다`() {
        assertEquals(60, resolve())
        assertEquals(60, resolve(qa = "", reset = ""))
        // RESET_TURNTERM은 env 파일에서 오므로 공백만 있는 값도 미설정으로 본다.
        assertEquals(60, resolve(qa = "", reset = "  "))
    }

    @Test
    fun `QA fence와 RESET_TURNTERM의 공백 정책은 의도적으로 다르다`() {
        // RESET_TURNTERM: 주변 공백 관용(운영 env 값).
        assertEquals(30, resolve(reset = " 30 "))
        // SCENARIO_QA_TURNTERM: 공백조차 거부(좁은 opt-in).
        // ScenarioMapSeedIT가 이미 고정한 계약이며, 여기서도 갈라지지 않게 못박는다.
        for (spaced in listOf(" 1", "1 ", " ")) {
            assertFailsWith<IllegalArgumentException>("QA fence가 공백을 허용함: '$spaced'") {
                resolve(qa = spaced)
            }
        }
    }

    @Test
    fun `RESET_TURNTERM이 시드 턴 주기가 된다`() {
        assertEquals(120, resolve(reset = "120"))
        assertEquals(30, resolve(reset = "30"))
        assertEquals(1, resolve(reset = "1"))
        // 앞뒤 공백은 env 파일에서 흔하다 — 값 자체가 유효하면 통과해야 한다.
        assertEquals(20, resolve(reset = " 20 "))
    }

    @Test
    fun `QA fence는 RESET_TURNTERM을 이긴다`() {
        // SCENARIO_QA_TURNTERM은 좁고 명시적인 opt-in이므로 운영값보다 우선한다.
        assertEquals(1, resolve(qa = "1", reset = "120"))
        assertEquals(1, resolve(qa = "1", reset = null))
    }

    @Test
    fun `허용되지 않은 RESET_TURNTERM은 조용히 무시되지 않고 부팅을 실패시킨다`() {
        // 조용한 폴백은 지금 닫는 결함(옵션이 말없이 버려짐)과 같은 실패 양상이다.
        for (bad in listOf("0", "7", "61", "-60", "abc", "60.0", "١٢٠")) {
            val e = assertFailsWith<IllegalArgumentException>("허용값이 아닌데 통과함: $bad") {
                resolve(reset = bad)
            }
            assertTrue(
                e.message!!.contains("RESET_TURNTERM"),
                "실패 사유가 어떤 설정 때문인지 말해야 한다: ${e.message}",
            )
        }
    }

    @Test
    fun `SCENARIO_QA_TURNTERM은 여전히 1만 받는다`() {
        val e = assertFailsWith<IllegalArgumentException> { resolve(qa = "5") }
        assertTrue(e.message!!.contains("SCENARIO_QA_TURNTERM"), e.message!!)
    }

    @Test
    fun `도메인 허용 턴 주기는 모든 기존 옵션을 유지한다`() {
        val expected = listOf(120, 60, 30, 20, 10, 5, 2, 1)
        assertEquals(expected, SeedBootstrap.ALLOWED_TURN_TERMS)
        for (term in expected) {
            assertEquals(term, resolve(reset = "$term"))
        }
    }
}
