package opensamguk.gameapi.reserve

import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandInboxRepository.AcceptedCommand
import opensamguk.infra.persistence.CommandInboxRepository.CommandKind
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Step 2 of the 8-step flow — reserve. Called by [opensamguk.gameapi.web.CommandController] for known
 * reservable commands; execution-time constraints are rechecked by the daemon.
 *
 * **Two intake models, selected by the command code** ([CommandWireMapper]):
 *
 *  A. **Turn-reserved `che_*` commands** (the default). Two effects, matching the TS split of "DB
 *     carries the reserved action, Redis carries the control signal" (devsam-core2026):
 *      1. **Durable reservation** — the action-code + arg is written to the `general_turn` ring buffer
 *         via the shared `:infra` [ReservedTurnRepository] (plain JDBC; `setGeneralTurn` faithful
 *         upsert). This is the SOURCE OF TRUTH the daemon reads when it processes the turn — NOT the
 *         Redis message.
 *      2. **Wake the daemon** — publish the EXISTING P0-B control signal to the MUTATION (command)
 *         stream. The reused wire variant is [TurnDaemonCommand.Run] with [RunReason.POKE] (the
 *         existing "wake/poke the daemon" control command — devsam-core2026 `daemon.poke()`).
 *     The reserve is ordered DB-first then publish: the durable reservation must exist before the
 *     daemon is woken, so a poke can never race ahead of its reserved action.
 *
 *  B. **Immediate daemon-command intake** (betting/auction + F4 Wave C2 single-actor commands). These
 *     are NOT turn-reserved: their engine handlers are driven by the
 *     [opensamguk.engine.run.TurnDaemonCommandDispatcher] off a TYPED [TurnDaemonCommand] on the
 *     command stream, NOT by the `general_turn` ring. For these we SKIP the ring write and publish the
 *     typed command itself (mapped from `{code, argJson, generalId}` by [CommandWireMapper]), so the
 *     daemon's `RedisCommandStream` → dispatcher → handler → `ChangeRecorder` → flush path executes
 *     the mutation. A `Run(POKE)` here would reach the dispatcher and return `null` (no handler),
 *     silently dropping the action.
 *
 * In BOTH models the envelope is the EXISTING [TurnDaemonCommandEnvelope], encoded into the one
 * `payload` field the engine-side `RedisCommandStream` consumer reads. NO new wire variant and NO
 * `:common`/wire change is introduced (OQ6 LEAD RULING) — game-api ONLY publishes; the daemon applies
 * (one-daemon-write rule).
 */
@Service
class CommandReserveService(
    private val reservedTurns: ReservedTurnRepository,
    private val commandInbox: CommandInboxRepository,
    private val commandResults: CommandResultRepository,
    private val redis: StringRedisTemplate,
    private val registry: CommandRegistry,
    processWorld: GameApiProcessWorld,
    @Value("\${opensamguk.profile:che:scenario_2}") profile: String,
    private val clock: Clock = Clock.systemUTC(),
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
    private val transactions: TransactionOperations,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val worldId: WorldId = processWorld.worldId
    private val commandStreamKey: String = TurnDaemonStreamKeys.of(profile, worldId).commandStream

    /** The outcome of a successful reserve: the generated request id the controller returns as 202. */
    data class ReserveResult(val requestId: String, val turnIdx: Int)

    /**
     * Submit the AVAILABLE command. Selects the intake model from [actionCode] ([CommandWireMapper]):
     *
     *  - **immediate daemon-command** intake (betting/auction + C2): publish the TYPED command (mapped
     *    from `{actionCode, argJson, generalId}`) — NO `general_turn` ring write (the daemon dispatches
     *    the typed command directly to its handler).
     *  - **turn-reserved `che_*`**: write the reserved action into the `general_turn` ring FIRST (DB is
     *    the source of truth), then poke the daemon with `Run(POKE)`.
     *
     * Returns the generated [ReserveResult.requestId] (echoed to the UI as the 202 requestId) in both.
     */
    fun reserve(generalId: Int, actionCode: String, turnIdx: Int = 0, argJson: String? = null): ReserveResult =
        reserveInternal(generalId, actionCode, turnIdx, argJson, ownerUserId = null)

    fun reserveForOwner(
        generalId: Int,
        actionCode: String,
        turnIdx: Int = 0,
        argJson: String? = null,
        ownerUserId: Int,
    ): ReserveResult = reserveInternal(generalId, actionCode, turnIdx, argJson, ownerUserId)

    private fun reserveInternal(
        generalId: Int,
        actionCode: String,
        turnIdx: Int,
        argJson: String?,
        ownerUserId: Int?,
    ): ReserveResult {
        val requestId = requestIds()

        // Model B — immediate daemon-command intake: publish the typed command, NO ring reservation.
        val intake = CommandWireMapper.toCommand(actionCode, generalId, requestId, argJson, ownerUserId)
        if (intake != null) {
            val envelope = TurnDaemonCommandEnvelope(
                requestId = requestId,
                sentAt = Instant.now(clock).toString(),
                command = intake,
            )
            val payload = encodeCommandPayload(envelope)
            transactions.executeWithoutResult {
                commandInbox.insertAccepted(
                    AcceptedCommand(
                        worldId = worldId,
                        requestId = requestId,
                        commandKind = CommandKind.IMMEDIATE,
                        intentFingerprint = intentFingerprint(CommandKind.IMMEDIATE, generalId, turnIdx, actionCode, argJson, ownerUserId),
                        generalId = generalId,
                        turnIdx = turnIdx,
                        actionCode = actionCode,
                        payloadJson = payload,
                        // OPENSAM-197 — 결과 조회 소유권 검사의 근거. 장수선택처럼 아직 소유하지 않은
                        // 장수로 내는 명령은 이 값이 유일한 제출자 증거다.
                        ownerUserId = ownerUserId,
                    ),
                ).throwIfConflict()
            }
            publishAfterCommit(envelope)
            return ReserveResult(requestId = requestId, turnIdx = turnIdx)
        }

        // Model A — turn-reserved che_* command.
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = Instant.now(clock).toString(),
            command = TurnDaemonCommand.Run(reason = RunReason.POKE),
        )
        val payload = encodeCommandPayload(envelope)
        val fingerprint = intentFingerprint(CommandKind.RESERVED_TURN, generalId, turnIdx, actionCode, argJson, ownerUserId)
        var inserted = false
        transactions.executeWithoutResult {
            val result = commandInbox.insertAccepted(
                AcceptedCommand(
                    worldId = worldId,
                    requestId = requestId,
                    commandKind = CommandKind.RESERVED_TURN,
                    intentFingerprint = fingerprint,
                    generalId = generalId,
                    turnIdx = turnIdx,
                    actionCode = actionCode,
                    payloadJson = payload,
                    ownerUserId = ownerUserId,
                ),
            )
            result.throwIfConflict()
            inserted = result is CommandInboxRepository.InsertResult.Inserted
            if (inserted) {
                reservedTurns.reserve(
                    worldId = worldId,
                    generalId = generalId,
                    turnIdx = turnIdx,
                    actionCode = actionCode,
                    argJson = argJson,
                    brief = registry.resolve(actionCode).name,
                    requestId = requestId,
                )
                commandResults.insertTerminalResult(
                    worldId = worldId,
                    row = CommandTerminalResultFactory.acceptedRow(
                        worldId = worldId,
                        requestId = requestId,
                        sentAt = Instant.now(clock),
                        type = "reservationAccepted",
                        commandKind = CommandKind.RESERVED_TURN,
                        actionCode = actionCode,
                        generalId = generalId,
                        turnIdx = turnIdx,
                    ),
                    expectedInboxStatuses = setOf("ACCEPTED"),
                )
            }
        }

        if (inserted) publishAfterCommit(envelope)
        return ReserveResult(requestId = requestId, turnIdx = turnIdx)
    }

    /**
     * Ring-less immediate publish — used by Join (B1) and other out-of-band daemon commands
     * that carry their own typed command (no general_turn reservation).
     */
    fun publishImmediate(command: TurnDaemonCommand): ReserveResult {
        val requestId = requestIds()
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = Instant.now(clock).toString(),
            command = command,
        )
        val payload = encodeCommandPayload(envelope)
        transactions.executeWithoutResult {
            commandInbox.insertAccepted(
                AcceptedCommand(
                    worldId = worldId,
                    requestId = requestId,
                    commandKind = CommandKind.IMMEDIATE,
                    intentFingerprint = intentFingerprint(CommandKind.IMMEDIATE, null, 0, command::class.simpleName, null, null),
                    generalId = null,
                    turnIdx = 0,
                    actionCode = command::class.simpleName,
                    payloadJson = payload,
                ),
            ).throwIfConflict()
        }
        publishAfterCommit(envelope)
        return ReserveResult(requestId = requestId, turnIdx = 0)
    }

    private fun publishAfterCommit(envelope: TurnDaemonCommandEnvelope) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        publishBestEffort(envelope)
                    }
                },
            )
        } else {
            publishBestEffort(envelope)
        }
    }

    private fun publishBestEffort(envelope: TurnDaemonCommandEnvelope) {
        try {
            publish(envelope)
            commandInbox.markRedisWakePublished(worldId, envelope.requestId, Instant.now(clock))
        } catch (_: Exception) {
            log.warn(
                "command Redis wake publish or marker failed; requestId={}, worldId={}, commandStreamKey={}",
                envelope.requestId,
                worldId.value,
                commandStreamKey,
            )
        }
    }

    private fun publish(envelope: TurnDaemonCommandEnvelope) {
        val record: ObjectRecord<String, Map<String, String>> = StreamRecords
            .newRecord()
            .ofObject(mapOf(WIRE_PAYLOAD_FIELD to encodeCommandPayload(envelope)))
            .withStreamKey(commandStreamKey)
        @Suppress("UNCHECKED_CAST")
        redis.opsForStream<Any, Any>().add(record as ObjectRecord<String, Any>)
    }

    private fun CommandInboxRepository.InsertResult.throwIfConflict() {
        if (this is CommandInboxRepository.InsertResult.Conflict) {
            throw IllegalStateException("command_inbox request_id conflict")
        }
    }

    private fun intentFingerprint(
        kind: CommandKind,
        generalId: Int?,
        turnIdx: Int?,
        actionCode: String?,
        argJson: String?,
        ownerUserId: Int?,
    ): String {
        val canonical = listOf(
            "v1",
            worldId.value.toString(),
            kind.name,
            generalId?.toString().orEmpty(),
            turnIdx?.toString().orEmpty(),
            actionCode.orEmpty(),
            argJson.orEmpty(),
            ownerUserId?.toString().orEmpty(),
        ).joinToString("\u001f")
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Bean wiring for reserve: the shared `:infra` [ReservedTurnRepository] is a plain JDBC class (no
 * `@Repository` stereotype), so it is published here against Boot's auto-configured
 * [NamedParameterJdbcTemplate]. The write path stays JDBC-only — no JPA `EntityManager`.
 */
@Configuration
class ReserveBeans {
    @Bean
    fun reservedTurnRepository(jdbc: NamedParameterJdbcTemplate): ReservedTurnRepository =
        ReservedTurnRepository(jdbc)

    @Bean
    fun commandInboxRepository(jdbc: NamedParameterJdbcTemplate): CommandInboxRepository =
        CommandInboxRepository(jdbc)

    @Bean
    fun commandResultRepository(jdbc: NamedParameterJdbcTemplate): CommandResultRepository =
        CommandResultRepository(jdbc)

    @Bean
    fun gameApiTransactionOperations(transactionManager: PlatformTransactionManager): TransactionOperations =
        TransactionTemplate(transactionManager)
}
