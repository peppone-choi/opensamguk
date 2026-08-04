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
 * 여기서 검증하는 것은 우선순위와 **허용 집합**이다. 허용 집합이
 * `.github/workflows/reset-game-server.yml`과 어긋나면 워크플로 리셋과 어드민 UI 리셋이
 * 서로 다른 월드를 만들기 때문에, 그 일치 자체를 테스트로 못박는다.
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

    /**
     * 허용 집합이 리셋 워크플로와 **정확히** 같은지 워크플로 파일에서 직접 읽어 대조한다.
     * 한쪽만 바뀌면 두 리셋 경로가 갈라지므로, 문서 주석이 아니라 실행되는 검사로 둔다.
     */
    @Test
    fun `허용 집합이 reset-game-server 워크플로와 일치한다`() {
        val workflow = generateSequence(java.io.File(".").absoluteFile) { it.parentFile }
            .map { java.io.File(it, ".github/workflows/reset-game-server.yml") }
            .firstOrNull { it.isFile }
        checkNotNull(workflow) { "reset-game-server.yml을 찾지 못했다 — 테스트 작업 디렉터리를 확인할 것" }

        val caseLine = workflow.readLines()
            .map { it.trim() }
            .firstOrNull { it.startsWith("120|") && it.endsWith(";;") }
        checkNotNull(caseLine) { "워크플로에서 턴 주기 case 라인을 찾지 못했다 — 형태가 바뀌었는지 확인할 것" }

        val fromWorkflow = caseLine.substringBefore(")").split("|").map { it.trim().toInt() }
        assertEquals(
            SeedBootstrap.ALLOWED_TURN_TERMS,
            fromWorkflow,
            "백엔드 허용 집합과 워크플로 허용 집합이 갈라졌다. 한쪽만 고치면 워크플로 리셋과 " +
                "어드민 UI 리셋이 서로 다른 월드를 만든다.",
        )
    }
}
