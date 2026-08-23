package opensamguk.engine.intake

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.NationSettingResult
import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.commandResultKey
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.run.TurnDaemonCommandDispatcher
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * W0-4 인테이크 결과 회신 채널 — 컨테이너 없는 단위 절반 (IntakeCommandConsumeDispatchTest 미러).
 *
 * 증명하는 두 계약:
 *  1. [TurnDaemonCommandDispatcher.dispatchEnvelopes] — 드레인된 엔벨로프의 requestId와 핸들러
 *     결과([opensamguk.common.wire.TurnDaemonCommandResult])를 쌍으로 묶는다. 컨트롤 커맨드
 *     (Run/POKE 등, dispatch == null)는 쌍을 만들지 않고, deny 결과(ok=false)도 성공과 동일하게
 *     회신된다 — 페이지가 성공 토스트를 위조하지 않으려면 deny가 반드시 돌아와야 한다.
 *  2. [RealtimePublisher.publishCommandResultPayload] — outbox payload를 [commandResultKey] 아래
 *     짧은 TTL로 SET한다(엔진 쓰기는 Redis 결과 채널뿐 — JPA 없음).
 *
 * Redis 왕복 자체는 Docker-게이트 [opensamguk.engine.run.TurnRunServiceIT]가 닫는다.
 */
class IntakeResultChannelTest {

    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world(): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            generals = listOf(
                TurnGeneral(
                    id = 10, name = "유비", nationId = 1, cityId = 5, troopId = 0,
                    stats = GeneralStats(80, 70, 60), experience = 0, dedication = 0,
                    officerLevel = 12, gold = 1000, turnTime = t0,
                ),
            ),
            nations = listOf(Nation(id = 1, name = "촉", color = "#0f0", gold = 1000)),
            worldId = opensamguk.common.world.WorldId((TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0)).id),
        ),
    )

    /** no-op 리포 프록시 — 이 테스트의 핸들러는 리포 메서드를 호출하지 않는다. */
    private inline fun <reified T> noopRepo(): T = java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.util.List::class.java -> emptyList<Any>()
            java.lang.Boolean.TYPE -> false
            else -> null
        }
    } as T

    private fun dispatcher(world: InMemoryTurnWorld, recorder: ChangeRecorder) = TurnDaemonCommandDispatcher(
        world, recorder,
        noopRepo<opensamguk.infra.read.AuctionRepository>(),
        noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
        noopRepo<opensamguk.infra.read.BoardPostRepository>(),
    )

    private fun envelope(requestId: String, command: TurnDaemonCommand) = TurnDaemonCommandEnvelope(
        requestId = requestId, sentAt = "0200-01-01T00:00:00Z", command = command,
    )

    @Test
    fun `dispatchEnvelopes는 각 인테이크 결과를 엔벨로프 requestId와 쌍으로 회신한다`() {
        val world = world()
        val recorder = ChangeRecorder()

        val results = dispatcher(world, recorder).dispatchEnvelopes(
            listOf(
                // 성공 인테이크 — 장수 10 존재 → ok=true.
                envelope("req-a", TurnDaemonCommand.TournamentEnroll(requestId = "req-a", generalId = 10, value = 1)),
                // 컨트롤 커맨드 — dispatch가 null을 돌려주므로 결과 쌍이 만들어지지 않는다.
                envelope("req-ctl", TurnDaemonCommand.Run(reason = RunReason.POKE)),
                // deny 인테이크 — 장수 99 부재 → ok=false + 사유. deny도 회신 대상이다.
                envelope("req-b", TurnDaemonCommand.TournamentEnroll(requestId = "req-b", generalId = 99, value = 1)),
            ),
        )

        assertEquals(listOf("req-a", "req-b"), results.map { it.first }, "컨트롤 커맨드는 건너뛰고 순서 보존")
        val okResult = results[0].second as NationSettingResult
        assertTrue(okResult.ok)
        assertEquals("tournamentEnroll", okResult.type)
        val denyResult = results[1].second as NationSettingResult
        assertFalse(denyResult.ok)
        assertEquals("장수가 존재하지 않습니다.", denyResult.reason)
    }

    @Test
    fun `malformed sentAt returns terminal denial and does not abort later envelopes`() {
        val malformed = TurnDaemonCommandEnvelope(
            requestId = "req-malformed",
            sentAt = "not-an-instant",
            command = TurnDaemonCommand.TournamentEnroll(requestId = "req-malformed", generalId = 10, value = 1),
        )
        val valid = envelope(
            "req-valid",
            TurnDaemonCommand.TournamentEnroll(requestId = "req-valid", generalId = 99, value = 1),
        )

        val results = dispatcher(world(), ChangeRecorder()).dispatchEnvelopes(listOf(malformed, valid))

        assertEquals(listOf("req-malformed", "req-valid"), results.map { it.first })
        val malformedResult = assertIs<CommandLifecycleResult>(results[0].second)
        assertFalse(malformedResult.ok)
        assertEquals("COMMAND_SENT_AT_INVALID", malformedResult.code)
        assertEquals("tournamentEnroll", malformedResult.actionCode)
        assertFalse(results[1].second.ok)
    }

    @Test
    fun `publishCommandResultPayload는 committedWorldVersion 엔벨로프를 짧은 TTL로 SET한다`() {
        val template = mock(StringRedisTemplate::class.java)

        @Suppress("UNCHECKED_CAST")
        val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOps)

        val publisher = RealtimePublisher(template, "che:scenario_2", WorldId(1))
        val payloadJson = WireJson.encodeToString(
            TurnDaemonEventEnvelope.serializer(),
            TurnDaemonEventEnvelope(
                requestId = "req-a",
                sentAt = "0200-01-01T01:00:00Z",
                event = TurnDaemonEvent.CommandResult(
                    NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1),
                ),
                committedWorldVersion = 12,
            ),
        )
        publisher.publishCommandResultPayload(
            requestId = "req-a",
            payloadJson = payloadJson,
        )

        val payload = ArgumentCaptor.forClass(String::class.java)
        verify(valueOps).set(
            eq(commandResultKey("che:scenario_2", WorldId(1), "req-a")),
            payload.capture(),
            eq(RealtimePublisher.COMMAND_RESULT_TTL),
        )

        val decoded = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), payload.value)
        assertEquals("req-a", decoded.requestId)
        assertEquals("0200-01-01T01:00:00Z", decoded.sentAt)
        assertEquals(12L, decoded.committedWorldVersion)
        val inner = (decoded.event as TurnDaemonEvent.CommandResult).result as NationSettingResult
        assertTrue(inner.ok)
        assertEquals("tournamentEnroll", inner.type)
        assertEquals(10, inner.generalId)
    }

    @Test
    fun `publishCommandResultPayload는 outbox payload를 그대로 결과 키에 SET한다`() {
        val template = mock(StringRedisTemplate::class.java)

        @Suppress("UNCHECKED_CAST")
        val valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(template.opsForValue()).thenReturn(valueOps)

        val publisher = RealtimePublisher(template, "che:scenario_2", WorldId(1))
        val payloadJson = """{"requestId":"req-outbox","event":{"type":"commandResult","result":{"type":"x","ok":true}}}"""
        publisher.publishCommandResultPayload("req-outbox", payloadJson)

        verify(valueOps).set(
            eq(commandResultKey("che:scenario_2", WorldId(1), "req-outbox")),
            eq(payloadJson),
            eq(RealtimePublisher.COMMAND_RESULT_TTL),
        )
    }

    @Test
    fun `결과 없는 dispatchEnvelopes는 빈 목록이고 발행 시도도 없다`() {
        val world = world()
        val recorder = ChangeRecorder()
        val template = mock(StringRedisTemplate::class.java)

        val results = dispatcher(world, recorder).dispatchEnvelopes(
            listOf(envelope("req-ctl", TurnDaemonCommand.Run(reason = RunReason.POKE))),
        )

        assertEquals(emptyList(), results)
        // 결과가 없으면 RealtimePublisher를 건드릴 일 자체가 없다(런 루프 계약의 단위 표현).
        verifyNoInteractions(template)
    }
}
