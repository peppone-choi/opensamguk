package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.FlushRecoveryGate
import opensamguk.engine.redis.CommandOutboxRelay
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.read.BoardPostRepository
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate

class OfflineDelegationFlushRetryTest {
    @Test
    fun `failed presence flush retries one unchanged lease and result batch`() {
        val worldId = WorldId(7)
        val world = InMemoryTurnWorld(WorldSnapshot(
            worldId = worldId,
            state = TurnWorldState(7, 190, 1, 3600, Instant.EPOCH, currentPhase = 1,
                config = mapOf("ruleProfile" to "HWIHA")),
            generals = listOf(TurnGeneral(12, "19", "player", 1, 1, 0,
                GeneralStats(70, 70, 70, 70, 70), 0, 0, 1, turnTime = Instant.EPOCH)),
        ))
        val envelope = TurnDaemonCommandEnvelope(
            requestId = "presence-retry",
            sentAt = Instant.EPOCH.toString(),
            command = TurnDaemonCommand.PresencePulse(12, 19),
        )
        val redis = mock(StringRedisTemplate::class.java)
        var delivered = false
        val stream = object : RedisCommandStream(redis, "hwiha:test", worldId, startId = "0") {
            override fun readEnvelopes(blockMs: Long): List<TurnDaemonCommandEnvelope> =
                if (delivered) emptyList() else listOf(envelope).also { delivered = true }
        }
        val payloads = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(
            NamedParameterJdbcTemplate(SimpleDriverDataSource()), TransactionTemplate(),
        ) {
            override fun flush(payload: FlushPayload) {
                payloads += payload
                if (payloads.size == 1) throw QueryTimeoutException("injected rollback")
            }
        }
        val handler = mock(ReservedTurnHandler::class.java)
        `when`(handler.recorder).thenReturn(ChangeRecorder())
        val lifecycle = TurnDaemonLifecycle(world, handler,
            reservedActionOf = { ReservedTurn("휴식", "") })
        val publisher = RealtimePublisher(redis, "hwiha:test", worldId)
        val relay = object : CommandOutboxRelay(
            mock(CommandResultRepository::class.java), publisher, worldId,
        ) {
            override fun publishPending(): Int = 0
        }
        val service = TurnRunService(world, stream, lifecycle, handler, flush, publisher,
            boardPostRepository = mock(BoardPostRepository::class.java), commandOutboxRelay = relay)

        assertFailsWith<QueryTimeoutException> { service.runIntakeCommands() }
        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, service.recoverySnapshot().mode)
        assertEquals(listOf("presence-retry"), payloads.single().commandResults.map { it.requestId })
        assertEquals(listOf("executionApplied"), payloads.single().commandResults.map { it.resultType })
        assertEquals(OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 1)),
            OfflineDelegationLease.read(payloads.single().updatedGenerals.single().meta))
        assertFailsWith<IllegalStateException> { service.runIntakeCommands() }

        assertTrue(service.retryRetainedFlush())
        assertSame(payloads.first(), payloads.last())
        assertEquals(2, payloads.size)
        assertEquals(OfflineDelegationLease(7, 12, 19, DelegationPhase(190, 1, 1)),
            OfflineDelegationLease.read(world.getGeneralById(12)!!.meta))
        assertEquals(0, service.runIntakeCommands())
        assertEquals(2, payloads.size, "no second lease or result batch after recovery")
    }
}
