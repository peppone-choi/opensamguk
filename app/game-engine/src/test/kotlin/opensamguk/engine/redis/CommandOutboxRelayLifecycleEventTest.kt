package opensamguk.engine.redis

import opensamguk.common.wire.CommandSettledEvent
import opensamguk.common.wire.RealtimeEvent
import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.PendingCommandOutboxEvent
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * OPENSAM-45 (1-b·1-c·1-d) — outbox 릴레이가 커밋된 배치마다 깨움 신호를 낸다.
 *
 * 고정하는 것은 네 가지다: (1) 폴링 키를 **전부 쓴 뒤에** 신호가 나간다(신호가 자기 결과를 앞지르면
 * FE가 빈 키를 읽고 PENDING으로 물러난다), (2) 신호에는 식별자조차 실리지 않는다(월드 전역
 * 브로드캐스트라 무엇이든 실으면 그것은 전역 공개다), (3) 배치당 한 번이다(내용이 같으므로 N번은
 * 소음일 뿐이다), (4) 발행이 실패해도 결과 처리는 유지된다(신호는 보조 경로다).
 */
class CommandOutboxRelayLifecycleEventTest {

    private val worldId = WorldId(1)
    private val clock = Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZoneOffset.UTC)

    private class RecordingPublisher(private val failEvents: Boolean = false) :
        RealtimePublisher(mock(StringRedisTemplate::class.java), "che:test", WorldId(1)) {
        val calls = mutableListOf<String>()
        val events = mutableListOf<RealtimeEvent>()

        override fun publishCommandResultPayload(requestId: String, payloadJson: String) {
            calls += "key:$requestId"
        }

        override fun publishRealtimeEvent(event: RealtimeEvent) {
            calls += "event:${event.type}"
            if (failEvents) error("redis down")
            events += event
        }
    }

    private class FakeRepository(private val rows: List<PendingCommandOutboxEvent>) :
        CommandResultRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val marked = mutableListOf<String>()

        override fun findPendingCommandResultOutbox(worldId: WorldId, limit: Int) = rows

        override fun markCommandOutboxPublished(worldId: WorldId, eventId: String, publishedAt: Instant): Boolean {
            marked += eventId
            return true
        }
    }

    private fun rows(vararg requestIds: String) =
        requestIds.mapIndexed { i, id -> PendingCommandOutboxEvent("e$i", id, """{"any":"payload"}""") }

    private fun relay(rows: List<PendingCommandOutboxEvent>, publisher: RecordingPublisher) =
        CommandOutboxRelay(FakeRepository(rows), publisher, worldId, clock)

    /** 식별자를 실으면 정본 엔드포인트에 소유권 검사가 없어(OPENSAM-197) 남의 결과가 읽힌다. */
    @Test
    fun `신호에는 시각 말고 아무것도 실리지 않는다`() {
        val publisher = RecordingPublisher()

        assertEquals(2, relay(rows("req-1", "req-2"), publisher).publishPending())

        val event = assertIs<CommandSettledEvent>(publisher.events.single())
        assertEquals(clock.instant().toString(), event.at)
    }

    /** 신호가 자기 결과를 앞지르면 FE 는 아직 없는 키를 읽고 PENDING 으로 물러난다. */
    @Test
    fun `폴링 키를 전부 쓴 뒤에 신호가 한 번 나간다`() {
        val publisher = RecordingPublisher()

        relay(rows("req-3", "req-4"), publisher).publishPending()

        assertEquals(listOf("key:req-3", "key:req-4", "event:commandSettled"), publisher.calls)
    }

    /** 커밋된 결과가 하나도 없으면 깨울 이유도 없다. */
    @Test
    fun `발행할 결과가 없으면 신호도 없다`() {
        val publisher = RecordingPublisher()

        assertEquals(0, relay(emptyList(), publisher).publishPending())

        assertTrue(publisher.calls.isEmpty())
    }

    /** 신호는 보조 경로다 — 발행이 터져도 결과 자체(키 + outbox 마킹)는 살아 있어야 한다. */
    @Test
    fun `신호 발행 실패가 결과 처리를 되돌리지 않는다`() {
        val publisher = RecordingPublisher(failEvents = true)
        val repository = FakeRepository(rows("req-5"))

        val published = CommandOutboxRelay(repository, publisher, worldId, clock).publishPending()

        assertEquals(1, published)
        assertEquals(listOf("e0"), repository.marked)
        assertTrue(publisher.events.isEmpty())
    }
}
