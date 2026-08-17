package opensamguk.engine.redis

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.wire.CommandSettledEvent
import opensamguk.common.wire.gameEventChannel
import opensamguk.common.world.WorldId
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OPENSAM-45 — `publishRealtimeEvent` 본체(채널 선택 + 다형 직렬화)를 실제로 태운다.
 *
 * 릴레이 테스트는 이 메서드를 가짜로 덮으므로 여기서 덮지 않으면 아무도 안 태운다. 특히
 * [CommandSettledEvent]는 `RealtimeEvent.kt` **밖**에 선언된 sealed 하위 타입이라
 * 다형 직렬화가 실제로 걸리는지 확인이 필요하다(안 걸리면 런타임에만 터진다).
 */
class RealtimePublisherEventTest {

    @Test
    fun `깨움 신호는 턴 이벤트와 같은 채널로 판별자를 달고 나간다`() {
        val template = mock(StringRedisTemplate::class.java)
        val worldId = WorldId(3)
        val publisher = RealtimePublisher(template, "che", worldId)

        publisher.publishRealtimeEvent(CommandSettledEvent(at = "0200-01-01T00:00:00.000Z", requestId = "req-9"))

        val channel = ArgumentCaptor.forClass(String::class.java)
        val body = ArgumentCaptor.forClass(Any::class.java)
        verify(template).convertAndSend(channel.capture(), body.capture())

        assertEquals(gameEventChannel("che", worldId), channel.value)
        val tree = Json.parseToJsonElement(body.value as String) as JsonObject
        assertEquals("commandSettled", tree["type"]?.jsonPrimitive?.content)
        assertEquals("req-9", tree["requestId"]?.jsonPrimitive?.content)
    }
}
