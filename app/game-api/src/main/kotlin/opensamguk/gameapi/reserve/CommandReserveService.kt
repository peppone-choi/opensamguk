package opensamguk.gameapi.reserve

import opensamguk.common.wire.RunReason
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.infra.persistence.ReservedTurnRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Step 2 of the 8-step flow — reserve. Called by [opensamguk.gameapi.web.CommandController] ONLY when
 * the E2 precheck returned `AVAILABLE`.
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
    private val redis: StringRedisTemplate,
    @Value("\${opensamguk.profile:che:scenario_2}") profile: String,
    private val clock: Clock = Clock.systemUTC(),
    private val requestIds: () -> String = { UUID.randomUUID().toString() },
) {
    private val commandStreamKey: String = TurnDaemonStreamKeys.of(profile).commandStream

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
    fun reserve(generalId: Int, actionCode: String, turnIdx: Int = 0, argJson: String? = null): ReserveResult {
        val requestId = requestIds()

        // Model B — immediate daemon-command intake: publish the typed command, NO ring reservation.
        val intake = CommandWireMapper.toCommand(actionCode, generalId, requestId, argJson)
        if (intake != null) {
            publish(
                TurnDaemonCommandEnvelope(
                    requestId = requestId,
                    sentAt = Instant.now(clock).toString(),
                    command = intake,
                )
            )
            return ReserveResult(requestId = requestId, turnIdx = turnIdx)
        }

        // Model A — turn-reserved che_* command.
        // 1. durable reservation FIRST (DB is the source of truth for the reserved action).
        reservedTurns.reserve(generalId = generalId, turnIdx = turnIdx, actionCode = actionCode, argJson = argJson)

        // 2. wake the daemon via the EXISTING P0-B control signal (Run/POKE) on the command stream.
        publish(
            TurnDaemonCommandEnvelope(
                requestId = requestId,
                sentAt = Instant.now(clock).toString(),
                command = TurnDaemonCommand.Run(reason = RunReason.POKE),
            )
        )
        return ReserveResult(requestId = requestId, turnIdx = turnIdx)
    }

    private fun publish(envelope: TurnDaemonCommandEnvelope) {
        val record: ObjectRecord<String, Map<String, String>> = StreamRecords
            .newRecord()
            .ofObject(mapOf(WIRE_PAYLOAD_FIELD to encodeCommandPayload(envelope)))
            .withStreamKey(commandStreamKey)
        @Suppress("UNCHECKED_CAST")
        redis.opsForStream<Any, Any>().add(record as ObjectRecord<String, Any>)
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
}
