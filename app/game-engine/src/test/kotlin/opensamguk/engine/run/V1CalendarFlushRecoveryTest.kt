package opensamguk.engine.run

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.world.WorldId
import opensamguk.engine.config.v1MonthlyClock
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
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.GameDate
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.MonthlyRngFactory
import opensamguk.logic.tick.PostUpdateMonthly
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.tick.ServerClock
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class V1CalendarFlushRecoveryTest {

    private val startYear = 181
    private val turnTerm = 60
    private val start = ServerClock.cutTurn(Instant.parse("0181-01-01T00:00:00Z"), turnTerm)

    @Test
    fun `phase two live clock is retained and committed through flush retry`() {
        val calls = AtomicInteger(0)
        val payloads = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), TransactionTemplate()) {
            override fun flush(payload: FlushPayload) {
                payloads += payload
                if (calls.incrementAndGet() == 1) {
                    throw QueryTimeoutException("flush timeout")
                }
            }
        }
        val fixture = fixture(flush)
        val runTime = ServerClock.addTurn(fixture.start, turnTerm)

        assertFailsWith<QueryTimeoutException> { fixture.service.runTick(runTime) }

        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, fixture.service.recoveryGate().mode())
        assertEquals(GameDate(181, 1, 2), stateDate(fixture.world))
        assertEquals(fixture.start, fixture.world.getState().lastTurnTime)
        val retained = payloads.single()
        assertEquals(181, retained.worldStateUpdate["current_year"])
        assertEquals(1, retained.worldStateUpdate["current_month"])
        assertEquals(2, retained.worldStateUpdate["current_phase"])
        assertEquals(runTime.toString(), retained.worldStateUpdate["last_turn_time"])

        assertTrue(fixture.service.retryRetainedFlush())

        assertEquals(2, calls.get())
        assertSame(retained, payloads.last())
        assertEquals(GameDate(181, 1, 2), stateDate(fixture.world))
        assertEquals(runTime, fixture.world.getState().lastTurnTime)
    }

    @Test
    fun `thirty six phase catch-up commits the calendar and restart resumes phase two`() {
        val payloads = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), TransactionTemplate()) {
            override fun flush(payload: FlushPayload) {
                payloads += payload
            }
        }
        val monthlyRuns = AtomicInteger()
        val fixture = fixture(flush, monthlyRuns = monthlyRuns)
        val yearBoundary = ServerClock.addTurn(fixture.start, turnTerm, 36)

        assertEquals(GameDate(181, 1, 1), stateDate(fixture.world))
        fixture.service.runTick(yearBoundary)

        assertEquals(12, monthlyRuns.get())
        assertEquals(GameDate(182, 1, 1), stateDate(fixture.world))
        assertEquals(yearBoundary, fixture.world.getState().lastTurnTime)
        val committed = payloads.single()
        assertEquals(182, committed.worldStateUpdate["current_year"])
        assertEquals(1, committed.worldStateUpdate["current_month"])
        assertEquals(1, committed.worldStateUpdate["current_phase"])
        assertEquals(yearBoundary.toString(), committed.worldStateUpdate["last_turn_time"])

        val restarted = fixture(
            flush = flush,
            state = stateFromCommittedPayload(committed),
            monthlyRuns = monthlyRuns,
        )
        assertEquals(GameDate(182, 1, 1), stateDate(restarted.world))
        assertEquals(yearBoundary, restarted.world.getState().lastTurnTime)

        val phaseTwoTime = ServerClock.addTurn(yearBoundary, turnTerm)
        restarted.service.runTick(phaseTwoTime)

        assertEquals(12, monthlyRuns.get())
        assertEquals(GameDate(182, 1, 2), stateDate(restarted.world))
        assertEquals(phaseTwoTime, restarted.world.getState().lastTurnTime)
        assertEquals(2, payloads.size)
        val phaseTwo = payloads.last()
        assertEquals(182, phaseTwo.worldStateUpdate["current_year"])
        assertEquals(1, phaseTwo.worldStateUpdate["current_month"])
        assertEquals(2, phaseTwo.worldStateUpdate["current_phase"])
        assertEquals(phaseTwoTime.toString(), phaseTwo.worldStateUpdate["last_turn_time"])
    }

    private fun fixture(
        flush: JdbcFlushExecutor,
        state: TurnWorldState? = null,
        monthlyRuns: AtomicInteger = AtomicInteger(),
    ): Fixture {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = state ?: TurnWorldState(
                    id = 1,
                    currentYear = startYear,
                    currentMonth = 1,
                    currentPhase = 1,
                    tickSeconds = turnTerm * 60,
                    lastTurnTime = start,
                    worldVersion = 0L,
                    writerEpoch = 1L,
                    meta = mapOf("startYear" to startYear, "startTime" to start.toString()),
                ),
                worldId = WorldId(1),
            ),
        )
        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = "calendar-recovery",
            startYear = startYear,
        )
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            reservedActionOf = { opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn("휴식", "") },
        )
        val pipeline = MonthlyPipeline(
            monthlyRngFactory = MonthlyRngFactory { _, _ ->
                monthlyRuns.incrementAndGet()
                RandUtil(LiteHashDrbg("calendar-recovery"))
            },
            clock = v1MonthlyClock(world, startYear, turnTerm),
            preUpdateMonthly = PreUpdateMonthly { true },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = PostUpdateMonthly { },
        )
        val redis = mock(StringRedisTemplate::class.java)
        val commandStream = object : RedisCommandStream(redis, "che:test", WorldId(1), startId = "0") {
            override fun readEnvelopes(blockMs: Long) = emptyList<opensamguk.common.wire.TurnDaemonCommandEnvelope>()
        }
        val service = TurnRunService(
            world = world,
            commandStream = commandStream,
            lifecycle = lifecycle,
            handler = handler,
            flushExecutor = flush,
            realtimePublisher = RealtimePublisher(redis, "che:test", WorldId(1)),
            pipeline = pipeline,
            eventDispatcher = EventDispatcher(EventStore(), EventActionFactory()),
        )
        return Fixture(service, world, start)
    }

    private fun stateFromCommittedPayload(payload: FlushPayload): TurnWorldState {
        val state = payload.worldStateUpdate
        return TurnWorldState(
            id = (state.getValue("id") as Number).toInt(),
            currentYear = (state.getValue("current_year") as Number).toInt(),
            currentMonth = (state.getValue("current_month") as Number).toInt(),
            currentPhase = (state.getValue("current_phase") as Number).toInt(),
            tickSeconds = turnTerm * 60,
            lastTurnTime = Instant.parse(state.getValue("last_turn_time") as String),
            worldVersion = (state.getValue("expected_world_version") as Number).toLong() + 1,
            writerEpoch = (state.getValue("writer_epoch") as Number).toLong(),
            meta = mapOf("startYear" to startYear, "startTime" to start.toString()),
        )
    }

    private fun stateDate(world: InMemoryTurnWorld): GameDate = world.getState().let {
        GameDate(it.currentYear, it.currentMonth, it.currentPhase)
    }

    private fun dummyJdbc(): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(SimpleDriverDataSource())

    private data class Fixture(
        val service: TurnRunService,
        val world: InMemoryTurnWorld,
        val start: Instant,
    )
}
