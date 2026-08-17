package opensamguk.gameapi.sse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-45 (1-d) — SSE 이벤트 이름은 payload의 `type`을 따른다.
 *
 * 이전에는 모든 payload를 `turnCompleted`로 내보냈다. 그 상태로 되돌리면 FE의 `commandSettled`
 * 리스너는 영원히 안 깨어나고 기능 전체가 죽는다 — 그런데 그 되돌림이 백엔드 테스트를 하나도
 * 빨갛게 만들지 않았다. 그래서 `fanOut`이 실제로 보내는 이벤트 조각을 여기서 직접 확인한다.
 */
class RealtimeRelayControllerEventNameTest {
    private val controller = RealtimeRelayController()

    /** `SseEventBuilder.build()`가 내는 SSE 프레임에서 `event:` 라인을 뽑는다. */
    private fun sentEventName(payload: String): String? =
        sentFrame(payload).lineSequence()
            .firstOrNull { it.startsWith("event:") }
            ?.removePrefix("event:")
            ?.trim()

    private fun sentFrame(payload: String): String =
        controller.eventFor(payload).build().joinToString("") { it.data.toString() }

    @Test
    fun `payload 의 type 이 이벤트 이름이 된다`() {
        assertEquals("commandSettled", sentEventName("""{"type":"commandSettled","requestId":"r1"}"""))
        assertEquals("messageCreated", sentEventName("""{"type":"messageCreated"}"""))
    }

    /** 이름을 못 읽는다고 신호를 버리면 기존 구독자가 턴 갱신을 잃는다 — 옛 이름으로 떨어진다. */
    @Test
    fun `type 이 없거나 깨진 payload 는 turnCompleted 로 떨어진다`() {
        for (payload in listOf("""{"at":"x"}""", "{not json", """{"type":""}""", """{"type":7}""", "[1,2]")) {
            assertEquals(RealtimeRelayController.DEFAULT_EVENT_NAME, sentEventName(payload), payload)
        }
    }

    @Test
    fun `payload 는 손대지 않고 그대로 실린다`() {
        val payload = """{"type":"turnCompleted","year":200}"""

        val frame = sentFrame(payload)

        assertTrue(frame.contains(payload), "sent frame=$frame")
    }
}
