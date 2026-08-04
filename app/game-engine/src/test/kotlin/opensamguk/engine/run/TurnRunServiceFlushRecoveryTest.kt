package opensamguk.engine.run

import opensamguk.common.world.WorldId
import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.engine.flush.FlushRecoveryGate
import opensamguk.engine.redis.CommandOutboxRelay
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import opensamguk.infra.persistence.CommandResultRepository
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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

    private data class Fixture(
        val service: TurnRunService,
        val world: InMemoryTurnWorld,
    )

    @Test
    fun `stale CAS flush enters RELOAD_REQUIRED and blocks intake and tick`() {
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                throw StaleWorldWriterException(1, 0L, 1L)
            }
        }
        val (service, world) = newFixture(flush)
        val t0 = Instant.parse("0200-01-01T00:00:00Z")
        val runTime = Instant.parse("0200-01-01T01:00:00Z")

        assertFailsWith<StaleWorldWriterException> { service.runTick(runTime) }

        val snap = service.recoverySnapshot()
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, snap.mode)
        assertFalse(snap.ready)
        assertFalse(service.recoveryGate().allowsIntakeOrTick())
        // Memory must stay pre-tick on failed flush (no clock advance).
        assertEquals(t0, world.getState().lastTurnTime)

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
        val (service, world) = newFixture(flush)
        val t0 = Instant.parse("0200-01-01T00:00:00Z")
        val runTime = Instant.parse("0200-01-01T01:00:00Z")

        assertFailsWith<QueryTimeoutException> { service.runTick(runTime) }

        val afterFail = service.recoverySnapshot()
        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, afterFail.mode)
        assertTrue(afterFail.hasRetainedPayload)
        // Still pre-tick until retry commits.
        assertEquals(t0, world.getState().lastTurnTime)
        assertFailsWith<IllegalStateException> { service.runIntakeCommands(1) }
        assertFailsWith<IllegalStateException> { service.runTick(runTime.plusSeconds(1)) }

        assertTrue(service.retryRetainedFlush())
        assertTrue(service.recoverySnapshot().ready)
        assertEquals(FlushRecoveryGate.Mode.READY, service.recoveryGate().mode())
        assertEquals(2, calls.get())

        // After successful FLUSH_RETRY, memory clock matches committed tick payload.
        assertEquals(runTime, world.getState().lastTurnTime, "lastTurnTime must advance to runTime after retry")
        assertEquals(runTime.plusSeconds(3600), service.nextRunTime(), "next due is runTime+tickSeconds")
        assertTrue(service.recoveryGate().allowsIntakeOrTick())
    }

    @Test
    fun `due tick preserves durable status cadence config and start time from the base payload`() {
        val payloads = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                payloads += payload
            }
        }
        val (service, world) = newFixture(flush)
        val startTime = Instant.parse("0200-01-01T00:00:00Z")
        val phpStartTime = startTime
            .atZone(ZoneId.of("Asia/Seoul"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        world.applyAdminWorldSettings(
            status = "PRE_OPEN",
            configPatch = linkedMapOf(
                "turnterm" to 30,
                "starttime" to phpStartTime,
                "startTime" to startTime.toString(),
            ),
            tickSeconds = 1_800,
        )

        service.runTick(startTime.plusSeconds(1_800))

        val worldState = payloads.single().worldStateUpdate
        assertEquals("PRE_OPEN", worldState["status"])
        assertEquals(1_800, worldState["tick_seconds"])
        assertEquals(30, (worldState["config"] as Map<*, *>)["turnterm"])
        assertEquals(startTime.toString(), worldState["start_time"])
    }

    @Test
    fun `RELOAD_REQUIRED does not retry same payload via retryRetainedFlush`() {
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                throw StaleWorldWriterException(1, 5L, 9L)
            }
        }
        val (service, world) = newFixture(flush)
        val t0 = Instant.parse("0200-01-01T00:00:00Z")
        assertFailsWith<StaleWorldWriterException> {
            service.runTick(Instant.parse("0200-01-01T01:00:00Z"))
        }
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, service.recoveryGate().mode())
        assertEquals(t0, world.getState().lastTurnTime)
        assertFailsWith<IllegalStateException> { service.retryRetainedFlush() }
    }

    @Test
    fun `claimed Redis wake is acked only after successful intake flush`() {
        val events = mutableListOf<String>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                events.add("flush")
            }
        }
        val wake = wakeEnvelope("1-0", "req-ack")
        val stream = fakeWakeStream(wake, events)
        val inbox = fakeInbox(wake.envelope)
        val (service, _) = newFixture(flush, commandStream = stream, commandInboxRepository = inbox)

        assertEquals(1, service.runIntakeCommands(1))

        assertEquals(listOf("flush", "ack:1-0"), events)
    }

    @Test
    fun `claimed Redis wake is not acked when intake flush fails`() {
        val events = mutableListOf<String>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                events.add("flush")
                throw QueryTimeoutException("flush timeout")
            }
        }
        val wake = wakeEnvelope("1-0", "req-no-ack")
        val stream = fakeWakeStream(wake, events)
        val inbox = fakeInbox(wake.envelope)
        val (service, _) = newFixture(flush, commandStream = stream, commandInboxRepository = inbox)

        assertFailsWith<QueryTimeoutException> { service.runIntakeCommands(1) }

        assertEquals(listOf("flush"), events)
    }

    @Test
    fun `reserved execution outbox relay still runs when post-commit wake ack fails`() {
        val events = mutableListOf<String>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                events.add("flush")
                assertEquals(listOf("executionApplied"), payload.commandResults.map { it.resultType })
                assertEquals(listOf(false), payload.commandResults.map { it.terminalizeInbox })
                assertEquals(listOf(10), payload.reservedGeneralTurnPulls.map { it.generalId })
                assertEquals(listOf(1 to 1), payload.reservedNationTurnPulls.map { it.nationId to it.officerLevel })
            }
        }
        val wake = wakeEnvelope("1-0", "req-exec")
        val stream = fakeWakeStream(wake, events, failAck = true)
        val inbox = fakeInbox(wake.envelope, CommandKind.RESERVED_TURN)
        val relay = fakeRelay(events)
        val (service, _) = newFixture(
            flush = flush,
            commandStream = stream,
            commandInboxRepository = inbox,
            commandOutboxRelay = relay,
            withDueGeneral = true,
            reservedTurn = ReservedTurn("휴식", "{}", requestId = "req-exec"),
        )

        val result = service.runTick(Instant.parse("0200-01-01T01:00:00Z"))

        assertEquals(1, result.handled.size)
        assertEquals(listOf("relay", "flush", "ack-fail:1-0", "relay"), events)
    }

    @Test
    fun `reserved ring pull is retained with execution result for flush retry`() {
        val calls = AtomicInteger(0)
        val payloads = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), dummyTx()) {
            override fun flush(payload: FlushPayload) {
                payloads.add(payload)
                if (calls.incrementAndGet() == 1) {
                    throw QueryTimeoutException("flush timeout")
                }
            }
        }
        val (service, world) = newFixture(
            flush = flush,
            withDueGeneral = true,
            reservedTurn = ReservedTurn("휴식", "{}", requestId = "req-retry-pull"),
        )

        assertFailsWith<QueryTimeoutException> {
            service.runTick(Instant.parse("0200-01-01T01:00:00Z"))
        }

        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, service.recoveryGate().mode())
        assertEquals(Instant.parse("0200-01-01T00:00:00Z"), world.getState().lastTurnTime)
        assertEquals(listOf(10), payloads.single().reservedGeneralTurnPulls.map { it.generalId })
        assertEquals(listOf("executionApplied"), payloads.single().commandResults.map { it.resultType })

        assertTrue(service.retryRetainedFlush())
        assertEquals(2, calls.get())
        assertEquals(listOf(10), payloads.last().reservedGeneralTurnPulls.map { it.generalId })
        assertEquals(listOf("executionApplied"), payloads.last().commandResults.map { it.resultType })
    }

    private fun newFixture(
        flush: JdbcFlushExecutor,
        commandStream: RedisCommandStream? = null,
        commandInboxRepository: CommandInboxRepository? = null,
        commandOutboxRelay: CommandOutboxRelay? = null,
        withDueGeneral: Boolean = false,
        reservedTurn: ReservedTurn = ReservedTurn("휴식", ""),
    ): Fixture {
        val t0 = Instant.parse("0200-01-01T00:00:00Z")
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    id = 1,
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    lastTurnTime = t0,
                    worldVersion = 0L,
                    writerEpoch = 1L,
                    meta = mapOf(
                        "startYear" to 200,
                        "startTime" to "0200-01-01T00:00:00Z",
                    ),
                ),
                generals = if (withDueGeneral) {
                    listOf(
                        TurnGeneral(
                            id = 10,
                            name = "장수",
                            nationId = 1,
                            cityId = 1,
                            troopId = 0,
                            stats = GeneralStats(leadership = 50, strength = 50, intelligence = 50),
                            experience = 0,
                            dedication = 0,
                            officerLevel = 1,
                            turnTime = t0.minusSeconds(1),
                        ),
                    )
                } else {
                    emptyList()
                },
                cities = if (withDueGeneral) {
                    listOf(City(id = 1, name = "도시", nationId = 1, level = 1))
                } else {
                    emptyList()
                },
                nations = if (withDueGeneral) {
                    listOf(Nation(id = 1, name = "국가", color = "#000", level = 1))
                } else {
                    emptyList()
                },
                worldId = WorldId(1),
            ),
        )
        val handler = ReservedTurnHandler(
            world,
            CommandRegistry(GeneralActionPipeline()),
            "00",
            184,
        )
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            pullNationTurnOf = { nationId, officerLevel ->
                handler.recorder.recordNationTurnPull(nationId, officerLevel)
            },
            pullGeneralTurnOf = { generalId ->
                handler.recorder.recordGeneralTurnPull(generalId)
            },
            reservedActionOf = { reservedTurn },
        )
        val redis = mock(StringRedisTemplate::class.java)
        val emptyStream = object : RedisCommandStream(redis, "che:test", WorldId(1), startId = "0") {
            override fun readEnvelopes(blockMs: Long) = emptyList<opensamguk.common.wire.TurnDaemonCommandEnvelope>()
        }
        val service = TurnRunService(
            world = world,
            commandStream = commandStream ?: emptyStream,
            lifecycle = lifecycle,
            handler = handler,
            flushExecutor = flush,
            realtimePublisher = RealtimePublisher(redis, "che:test", WorldId(1)),
            commandInboxRepository = commandInboxRepository,
            commandOutboxRelay = commandOutboxRelay,
        )
        return Fixture(service, world)
    }

    private fun wakeEnvelope(messageId: String, requestId: String): RedisCommandStream.WakeEnvelope =
        RedisCommandStream.WakeEnvelope(
            messageId = messageId,
            envelope = TurnDaemonCommandEnvelope(
                requestId = requestId,
                sentAt = "0200-01-01T00:00:00Z",
                command = TurnDaemonCommand.Run(reason = RunReason.POKE),
            ),
        )

    private fun fakeWakeStream(
        wake: RedisCommandStream.WakeEnvelope,
        events: MutableList<String>,
        failAck: Boolean = false,
    ): RedisCommandStream {
        val emitted = AtomicInteger(0)
        return object : RedisCommandStream(mock(StringRedisTemplate::class.java), "che:test", WorldId(1), startId = "0") {
            override fun readWakeEnvelopes(blockMs: Long): List<RedisCommandStream.WakeEnvelope> =
                if (emitted.incrementAndGet() == 1) listOf(wake) else emptyList()

            override fun acknowledgeWake(messageIds: List<String>): Long {
                if (failAck) {
                    messageIds.forEach { events.add("ack-fail:$it") }
                    throw IllegalStateException("ack down")
                }
                messageIds.forEach { events.add("ack:$it") }
                return messageIds.size.toLong()
            }
        }
    }

    private fun fakeInbox(
        envelope: TurnDaemonCommandEnvelope,
        kind: CommandKind = CommandKind.IMMEDIATE,
    ): CommandInboxRepository =
        object : CommandInboxRepository(dummyJdbc()) {
            override fun claimForExecution(
                worldId: WorldId,
                requestIds: List<String>,
                now: Instant,
                lease: java.time.Duration,
            ): List<ClaimedCommand> =
                if (requestIds.contains(envelope.requestId)) {
                    listOf(
                        ClaimedCommand(
                            requestId = envelope.requestId,
                            payloadJson = "{}",
                            envelope = envelope,
                            commandKind = kind,
                            generalId = null,
                            turnIdx = null,
                            actionCode = null,
                        ),
                    )
                } else {
                    emptyList()
                }
        }

    private fun fakeRelay(events: MutableList<String>): CommandOutboxRelay =
        object : CommandOutboxRelay(
            mock(CommandResultRepository::class.java),
            RealtimePublisher(mock(StringRedisTemplate::class.java), "che:test", WorldId(1)),
            WorldId(1),
        ) {
            override fun publishPending(): Int {
                events.add("relay")
                return 0
            }
        }

    private fun dummyJdbc(): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(SimpleDriverDataSource())

    private fun dummyTx(): TransactionTemplate = TransactionTemplate()
}
