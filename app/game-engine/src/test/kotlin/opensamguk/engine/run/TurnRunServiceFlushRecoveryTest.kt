package opensamguk.engine.run

import opensamguk.common.world.WorldId
import opensamguk.engine.flush.FlushRecoveryGate
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.persistence.StaleWorldWriterException
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OPENSAM-132 — real [TurnRunService] recovery entry points (not free-standing gate only).
 *
 * Flush failures come from shipped [JdbcFlushExecutor] subclasses so onFlushFailure runs
 * on the production path after [runTick]/[retryRetainedFlush].
 */
class TurnRunServiceFlushRecoveryTest {

    @Test
    fun `stale CAS flush enters RELOAD_REQUIRED and blocks intake and tick`() {
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                throw StaleWorldWriterException(1, 0L, 1L)
            }
        }
        val service = newService(flush)
        val runTime = Instant.parse("0200-01-01T01:00:00Z")

        assertFailsWith<StaleWorldWriterException> { service.runTick(runTime) }

        val snap = service.recoverySnapshot()
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, snap.mode)
        assertFalse(snap.ready)
        assertFalse(service.recoveryGate().allowsIntakeOrTick())

        assertFailsWith<IllegalStateException> { service.runIntakeCommands(1) }
        assertFailsWith<IllegalStateException> { service.runTick(runTime.plusSeconds(3600)) }
    }

    @Test
    fun `transient flush enters FLUSH_RETRY then retryRetainedFlush resumes READY`() {
        val calls = AtomicInteger(0)
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                if (calls.incrementAndGet() == 1) {
                    throw QueryTimeoutException("flush timeout")
                }
            }
        }
        val service = newService(flush)
        val runTime = Instant.parse("0200-01-01T01:00:00Z")

        assertFailsWith<QueryTimeoutException> { service.runTick(runTime) }

        val afterFail = service.recoverySnapshot()
        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, afterFail.mode)
        assertTrue(afterFail.hasRetainedPayload)
        assertFailsWith<IllegalStateException> { service.runIntakeCommands(1) }
        assertFailsWith<IllegalStateException> { service.runTick(runTime.plusSeconds(1)) }

        assertTrue(service.retryRetainedFlush())
        assertTrue(service.recoverySnapshot().ready)
        assertEquals(FlushRecoveryGate.Mode.READY, service.recoveryGate().mode())
        assertEquals(2, calls.get())

        // Intake allowed again (empty redis stream may NPE — use gate only)
        assertTrue(service.recoveryGate().allowsIntakeOrTick())
    }

    @Test
    fun `RELOAD_REQUIRED does not retry same payload via retryRetainedFlush`() {
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                throw StaleWorldWriterException(1, 5L, 9L)
            }
        }
        val service = newService(flush)
        assertFailsWith<StaleWorldWriterException> {
            service.runTick(Instant.parse("0200-01-01T01:00:00Z"))
        }
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, service.recoveryGate().mode())
        assertFailsWith<IllegalStateException> { service.retryRetainedFlush() }
    }

    private fun newService(flush: JdbcFlushExecutor): TurnRunService {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = Instant.parse("0200-01-01T00:00:00Z"),
                    worldVersion = 0L,
                    writerEpoch = 1L,
                ),
                worldId = WorldId(1),
            ),
        )
        val handler = ReservedTurnHandler(
            world,
            CommandRegistry(GeneralActionPipeline()),
            "00",
            184,
        )
        val lifecycle = TurnDaemonLifecycle(world, handler) { ReservedTurn("휴식", "") }
        val redis = mock(StringRedisTemplate::class.java)
        val emptyStream = object : RedisCommandStream(redis, "che:test", WorldId(1), startId = "0") {
            override fun readEnvelopes(blockMs: Long) = emptyList<opensamguk.common.wire.TurnDaemonCommandEnvelope>()
        }
        return TurnRunService(
            world = world,
            commandStream = emptyStream,
            lifecycle = lifecycle,
            handler = handler,
            flushExecutor = flush,
            realtimePublisher = RealtimePublisher(redis, "che:test", WorldId(1)),
        )
    }

    private fun dummyJdbc(): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(SimpleDriverDataSource())

    private fun dummyTx(): TransactionTemplate = TransactionTemplate()
}
