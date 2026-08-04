package opensamguk.engine.intake

import opensamguk.common.wire.CommandLifecycleResult
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.CommandOutboxRelay
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.SimpleDriverDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileIconSyncLifecycleTest {
    private val t0 = Instant.parse("0200-01-01T00:00:00Z")

    private fun world() = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600, lastTurnTime = t0),
            worldId = WorldId(1),
        ),
    )

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

    private fun commandStream(envelope: TurnDaemonCommandEnvelope): RedisCommandStream {
        var emitted = false
        return object : RedisCommandStream(mock(StringRedisTemplate::class.java), "che:test", WorldId(1), startId = "0") {
            override fun readEnvelopes(blockMs: Long): List<TurnDaemonCommandEnvelope> =
                if (emitted) {
                    emptyList()
                } else {
                    emitted = true
                    listOf(envelope)
                }
        }
    }

    private fun service(
        world: InMemoryTurnWorld,
        stream: RedisCommandStream,
        flush: JdbcFlushExecutor,
    ): TurnRunService {
        val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()), "00", 184)
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            pullNationTurnOf = { _, _ -> },
            pullGeneralTurnOf = {},
            reservedActionOf = { ReservedTurn("휴식", "") },
        )
        val redis = mock(StringRedisTemplate::class.java)
        return TurnRunService(
            world = world,
            commandStream = stream,
            lifecycle = lifecycle,
            handler = handler,
            flushExecutor = flush,
            realtimePublisher = RealtimePublisher(redis, "che:test", WorldId(1)),
            auctionRepository = noopRepo<opensamguk.infra.read.AuctionRepository>(),
            auctionBidRepository = noopRepo<opensamguk.infra.read.AuctionBidRepository>(),
            boardPostRepository = noopRepo<opensamguk.infra.read.BoardPostRepository>(),
            commandOutboxRelay = object : CommandOutboxRelay(
                mock(CommandResultRepository::class.java),
                RealtimePublisher(redis, "che:test", WorldId(1)),
                WorldId(1),
            ) {
                override fun publishPending(): Int = 0
            },
        )
    }

    private fun dummyJdbc(): NamedParameterJdbcTemplate =
        NamedParameterJdbcTemplate(SimpleDriverDataSource())

    @Test
    fun `durable immediate no-op profile icon sync produces a terminal command result row`() {
        val captured = mutableListOf<FlushPayload>()
        val flush = object : JdbcFlushExecutor(dummyJdbc(), TransactionTemplate()) {
            override fun flush(payload: FlushPayload) {
                captured += payload
            }
        }
        val envelope = TurnDaemonCommandEnvelope(
            requestId = "req-profile-icon",
            sentAt = t0.toString(),
            command = TurnDaemonCommand.ProfileIconSync(
                userId = 7L,
                picture = "abcd1234.jpg",
                imgsvr = 1,
                grade = 5,
            ),
        )

        assertEquals(1, service(world(), commandStream(envelope), flush).runIntakeCommands())

        val row = captured.single().commandResults.single()
        assertEquals("profileIconSync", row.resultType)
        assertTrue(row.ok)
        assertTrue(row.terminalizeInbox)
        val decoded = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), row.envelopeJson)
        val terminal = assertIs<CommandLifecycleResult>((decoded.event as TurnDaemonEvent.CommandResult).result)
        assertEquals("profileIconSync", terminal.type)
        assertEquals("IMMEDIATE", terminal.commandKind)
        assertEquals("ProfileIconSync", terminal.actionCode)
        assertEquals(0, terminal.turnIdx)
    }
}
