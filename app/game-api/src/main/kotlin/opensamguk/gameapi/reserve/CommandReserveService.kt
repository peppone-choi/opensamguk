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
 * Two effects, matching the TS split of "DB carries the reserved action, Redis carries the control
 * signal" (devsam-core2026):
 *
 *  1. **Durable reservation** — the action-code + arg is written to the `general_turn` ring buffer via
 *     the shared `:infra` [ReservedTurnRepository] (plain JDBC; `setGeneralTurn` faithful upsert). This
 *     is the SOURCE OF TRUTH the daemon reads when it processes the turn — NOT the Redis message.
 *  2. **Wake the daemon** — publish the EXISTING P0-B control signal to the MUTATION (command) stream.
 *     The reused wire variant is [TurnDaemonCommand.Run] with [RunReason.POKE] (the existing
 *     "wake/poke the daemon" control command — devsam-core2026 `daemon.poke()`); NO new wire variant
 *     and NO `:common`/wire change is introduced (OQ6 LEAD RULING). The envelope is the EXISTING
 *     [TurnDaemonCommandEnvelope], encoded into the one `payload` field the engine-side
 *     `RedisCommandStream` consumer reads.
 *
 * The reserve is ordered DB-first then publish: the durable reservation must exist before the daemon
 * is woken, so a poke can never race ahead of its reserved action.
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
     * Persist the reserved [actionCode] (+ optional [argJson]) into the `general_turn` slot, then poke
     * the daemon. Returns the generated [ReserveResult.requestId].
     */
    fun reserve(generalId: Int, actionCode: String, turnIdx: Int = 0, argJson: String? = null): ReserveResult {
        // 1. durable reservation FIRST (DB is the source of truth for the reserved action).
        reservedTurns.reserve(generalId = generalId, turnIdx = turnIdx, actionCode = actionCode, argJson = argJson)

        // 2. wake the daemon via the EXISTING P0-B control signal (Run/POKE) on the command stream.
        val requestId = requestIds()
        val envelope = TurnDaemonCommandEnvelope(
            requestId = requestId,
            sentAt = Instant.now(clock).toString(),
            command = TurnDaemonCommand.Run(reason = RunReason.POKE),
        )
        publish(envelope)
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
