package opensamguk.gameapi.reserve

import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.CommandResultRow
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CommandReserveServiceTest {
    private class RecordingReservedTurns :
        ReservedTurnRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        data class ReserveCall(
            val worldId: WorldId,
            val generalId: Int,
            val turnIdx: Int,
            val actionCode: String?,
            val argJson: String?,
            val brief: String,
            val requestId: String?,
        )

        val reserves = mutableListOf<ReserveCall>()

        override fun reserve(
            worldId: WorldId,
            generalId: Int,
            turnIdx: Int,
            actionCode: String?,
            argJson: String?,
            brief: String,
            requestId: String?,
        ) {
            reserves += ReserveCall(worldId, generalId, turnIdx, actionCode, argJson, brief, requestId)
        }
    }

    private class RecordingInbox :
        CommandInboxRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val accepted = mutableListOf<AcceptedCommand>()
        private val byRequestId = linkedMapOf<String, AcceptedCommand>()

        override fun insertAccepted(command: AcceptedCommand): InsertResult {
            val existing = byRequestId[command.requestId]
            if (existing != null) {
                return if (existing.intentFingerprint == command.intentFingerprint) {
                    InsertResult.ExistingSame
                } else {
                    InsertResult.Conflict(existing.intentFingerprint)
                }
            }
            accepted += command
            byRequestId[command.requestId] = command
            return InsertResult.Inserted
        }
    }

    private class RecordingResults :
        CommandResultRepository(mock(NamedParameterJdbcTemplate::class.java)) {
        val rows = mutableListOf<CommandResultRow>()

        override fun insertTerminalResult(
            worldId: WorldId,
            row: CommandResultRow,
            expectedInboxStatuses: Collection<String>,
        ) {
            rows += row
        }
    }

    private object TestTransactions : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T? =
            action.doInTransaction(SimpleTransactionStatus())

        override fun executeWithoutResult(action: java.util.function.Consumer<TransactionStatus>) {
            action.accept(SimpleTransactionStatus())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun redis(): StringRedisTemplate {
        val redis = mock(StringRedisTemplate::class.java)
        val streamOps = mock(StreamOperations::class.java) as StreamOperations<String, Any, Any>
        `when`(redis.opsForStream<Any, Any>()).thenReturn(streamOps)
        return redis
    }

    @Test
    fun `turn-reserved commands store action definition name as brief for the reserved table`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-brief" },
            transactions = TestTransactions,
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)

        assertEquals(1, reservedTurns.reserves.size)
        assertEquals(WorldId(1), reservedTurns.reserves.single().worldId)
        assertEquals("che_견문", reservedTurns.reserves.single().actionCode)
        assertEquals("견문", reservedTurns.reserves.single().brief)
        assertEquals("req-brief", reservedTurns.reserves.single().requestId)
        assertEquals("req-brief", inbox.accepted.single().requestId)
        assertEquals(CommandInboxRepository.CommandKind.RESERVED_TURN, inbox.accepted.single().commandKind)
        assertEquals("reservationAccepted", results.rows.single().resultType)
    }

    @Test
    fun `immediate command inserts inbox before publishing and tolerates redis failure`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val redis = mock(StringRedisTemplate::class.java)
        `when`(redis.opsForStream<Any, Any>()).thenThrow(IllegalStateException("redis down"))
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis,
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-immediate" },
            transactions = TestTransactions,
        )

        val result = service.reserve(generalId = 10, actionCode = "sendMessage", turnIdx = 0, argJson = """{"msg":"x"}""")

        assertEquals("req-immediate", result.requestId)
        assertEquals(0, reservedTurns.reserves.size)
        assertEquals("req-immediate", inbox.accepted.single().requestId)
        assertEquals(CommandInboxRepository.CommandKind.IMMEDIATE, inbox.accepted.single().commandKind)
        assertEquals(emptyList(), results.rows)
    }

    @Test
    fun `same request id and same reserved intent does not rewrite ring`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-same" },
            transactions = TestTransactions,
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)
        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)

        assertEquals(1, inbox.accepted.size)
        assertEquals(1, reservedTurns.reserves.size)
        assertEquals(1, results.rows.size)
    }

    @Test
    fun `same request id and different reserved intent is rejected before ring rewrite`() {
        val reservedTurns = RecordingReservedTurns()
        val inbox = RecordingInbox()
        val results = RecordingResults()
        val service = CommandReserveService(
            reservedTurns = reservedTurns,
            commandInbox = inbox,
            commandResults = results,
            redis = redis(),
            registry = CommandRegistry(GeneralActionPipeline()),
            processWorld = GameApiProcessWorld(1),
            profile = "che:scenario_2",
            clock = Clock.fixed(Instant.parse("0200-01-01T00:00:00Z"), ZoneOffset.UTC),
            requestIds = { "req-conflict" },
            transactions = TestTransactions,
        )

        service.reserve(generalId = 10, actionCode = "che_견문", turnIdx = 0, argJson = null)
        assertFailsWith<IllegalStateException> {
            service.reserve(generalId = 10, actionCode = "che_농지개간", turnIdx = 0, argJson = null)
        }

        assertEquals(1, inbox.accepted.size)
        assertEquals(1, reservedTurns.reserves.size)
        assertEquals("che_견문", reservedTurns.reserves.single().actionCode)
        assertEquals(1, results.rows.size)
    }
}
