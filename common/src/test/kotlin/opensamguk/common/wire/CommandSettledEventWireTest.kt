package opensamguk.common.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OPENSAM-45 (1-a) — 깨움 신호의 wire 계약.
 *
 * 고정하는 것은 두 가지다: (1) 판별자 문자열 — 브라우저는 SSE 이벤트 **이름**으로 분기하고 그 이름은
 * payload의 `type`에서 나오므로, 이름이 바뀌면 FE 리스너가 조용히 아무것도 못 받는다.
 * (2) 필드가 `at` **하나뿐**이라는 것 — 이 payload는 접속한 모든 브라우저에 뿌려지므로 무엇이든
 * 하나 실리는 순간 그것은 월드 전역 공개다. `requestId`조차 안 된다: 정본 엔드포인트에 소유권
 * 검사가 없어(OPENSAM-197) 값을 아는 사람이 곧 결과를 읽는 사람이다.
 */
class CommandSettledEventWireTest {

    @Test
    fun `commandSettled 는 판별자와 함께 왕복한다`() {
        val event: RealtimeEvent = CommandSettledEvent(at = "0200-01-01T00:00:00.000Z")

        val raw = WireJson.encodeToString(RealtimeEvent.serializer(), event)
        val tree = Json.parseToJsonElement(raw) as JsonObject

        assertEquals("commandSettled", tree["type"]?.jsonPrimitive?.content)
        assertEquals(event, WireJson.decodeFromString(RealtimeEvent.serializer(), raw))
    }

    /** 필드가 늘어나면 그 값은 곧바로 월드 전역 브로드캐스트가 된다 — 늘리려면 이 테스트를 먼저 보라. */
    @Test
    fun `payload 에는 at 만 있다`() {
        val raw = WireJson.encodeToString(
            RealtimeEvent.serializer(),
            CommandSettledEvent(at = "0200-01-01T00:00:00.000Z"),
        )

        val tree = Json.parseToJsonElement(raw) as JsonObject

        assertEquals(setOf("type", "at"), tree.keys)
    }
}
