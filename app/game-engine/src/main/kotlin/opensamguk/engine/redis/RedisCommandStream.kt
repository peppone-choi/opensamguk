package opensamguk.engine.redis

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.world.WorldId
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.WireJson
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.Consumer
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.domain.Range
import java.time.Duration

/**
 * Faithful port of `redisCommandStream.ts` `readRemoteCommands`: an `XREAD BLOCK <ms> COUNT 100`
 * from a per-consumer cursor `lastId` (starting `'$'` = only-new), one `payload` JSON field per
 * message, advancing `lastId` per consumed record. Unparseable payloads are skipped but still
 * advance the cursor.
 *
 * Stream keys come from the `:common` [TurnDaemonStreamKeys.of] — there is no engine-local key
 * helper; the key strings are locked by the `:common` StreamKeysTest.
 */
open class RedisCommandStream(
    private val template: StringRedisTemplate,
    profileName: String,
    worldId: WorldId,
    startId: String = "\$",
    private val consumerGroup: String = "game-engine",
    private val consumerName: String = "world-${worldId.value}",
    private val pendingClaimIdle: Duration = Duration.ofSeconds(30),
) {
    private val keys: TurnDaemonStreamKeys = TurnDaemonStreamKeys.of(profileName, worldId)
    private var lastId: String

    init {
        // `'$'` means "only messages newer than the consumer's start point". Spring Data Redis
        // would forward a literal `$` to XREAD, which Redis resolves to the stream tail at *read*
        // time — so any message enqueued between construction and the first read would be skipped.
        // To match the TS `startId ?? '$'` intent (only-new relative to *construction*), resolve
        // `$` to the current stream tail here. An explicit id (e.g. "0") is used verbatim.
        lastId = if (startId == "\$") currentTail() else startId
        ensureConsumerGroup()
    }

    private fun currentTail(): String {
        val info = try {
            template.opsForStream<Any, Any>().info(keys.commandStream)
        } catch (_: Exception) {
            null
        }
        return info?.lastGeneratedId() ?: "0"
    }

    fun commandStreamKey(): String = keys.commandStream

    fun lastId(): String = lastId

    data class WakeEnvelope(
        val messageId: String,
        val envelope: TurnDaemonCommandEnvelope,
    )

    /**
     * `XREAD BLOCK <blockMs> COUNT 100` from the current cursor. Returns the parsed commands and
     * advances [lastId] to the id of the last record read (even when its payload fails to parse).
     */
    fun readCommands(blockMs: Long): List<TurnDaemonCommand> =
        readEnvelopes(blockMs).map { it.command }

    /**
     * W0-4 인테이크 결과 회신 채널 — [readCommands]와 동일한 드레인이지만 **엔벨로프째** 돌려준다.
     * per-requestId 결과 회신([opensamguk.engine.redis.RealtimePublisher.publishCommandResult])은
     * 엔벨로프의 `requestId`가 필요하므로 커맨드만 벗겨내면 안 된다. 커서 전진 규약은 동일:
     * 파싱 실패 payload도 [lastId]를 전진시키고 건너뛴다.
     */
    open fun readEnvelopes(blockMs: Long): List<TurnDaemonCommandEnvelope> {
        val options = StreamReadOptions.empty().count(100).block(Duration.ofMillis(blockMs))
        val offset = StreamOffset.create(keys.commandStream, ReadOffset.from(lastId))
        val records: List<MapRecord<String, Any, Any>> =
            template.opsForStream<Any, Any>().read(options, offset) ?: return emptyList()

        val envelopes = mutableListOf<TurnDaemonCommandEnvelope>()
        for (record in records) {
            lastId = record.id.value
            val payload = record.value[WIRE_PAYLOAD_FIELD]?.toString() ?: continue
            val envelope = parseEnvelope(payload) ?: continue
            envelopes.add(envelope)
        }
        return envelopes
    }

    open fun readWakeEnvelopes(blockMs: Long): List<WakeEnvelope> {
        val options = StreamReadOptions.empty().count(100).block(Duration.ofMillis(blockMs))
        val records = readGroupRecords(options, ReadOffset.lastConsumed())
        if (records.isNotEmpty()) return records.toWakeEnvelopes()
        val ownPending = readGroupRecords(StreamReadOptions.empty().count(100), ReadOffset.from("0"))
        if (ownPending.isNotEmpty()) return ownPending.toWakeEnvelopes()
        return claimStaleWakeEnvelopes(pendingClaimIdle)
    }

    open fun claimStaleWakeEnvelopes(
        minIdleTime: Duration = pendingClaimIdle,
        limit: Long = 100,
    ): List<WakeEnvelope> {
        val pending = template.opsForStream<Any, Any>()
            .pending(keys.commandStream, consumerGroup, Range.unbounded<String>(), limit)
        val staleIds: Array<RecordId> = pending.toList()
            .filter { it.consumerName != consumerName && it.elapsedTimeSinceLastDelivery >= minIdleTime }
            .map { it.id }
            .toTypedArray()
        if (staleIds.isEmpty()) return emptyList()
        return template.opsForStream<Any, Any>()
            .claim(keys.commandStream, consumerGroup, consumerName, minIdleTime, *staleIds)
            .orEmpty()
            .toWakeEnvelopes()
    }

    open fun acknowledgeWake(messageIds: List<String>): Long {
        if (messageIds.isEmpty()) return 0
        val ids = messageIds.map { RecordId.of(it) }.toTypedArray()
        return template.opsForStream<Any, Any>().acknowledge(keys.commandStream, consumerGroup, *ids) ?: 0L
    }

    private fun readGroupRecords(
        options: StreamReadOptions,
        offset: ReadOffset,
    ): List<MapRecord<String, Any, Any>> =
        template.opsForStream<Any, Any>().read(
            Consumer.from(consumerGroup, consumerName),
            options,
            StreamOffset.create(keys.commandStream, offset),
        ).orEmpty()

    private fun List<MapRecord<String, Any, Any>>.toWakeEnvelopes(): List<WakeEnvelope> {
        val envelopes = mutableListOf<WakeEnvelope>()
        for (record in this) {
            val payload = record.value[WIRE_PAYLOAD_FIELD]?.toString() ?: continue
            val envelope = parseEnvelope(payload) ?: continue
            envelopes.add(WakeEnvelope(record.id.value, envelope))
        }
        return envelopes
    }

    private fun ensureConsumerGroup() {
        runCatching {
            template.execute { connection ->
                connection.execute(
                    "XGROUP",
                    "CREATE".toByteArray(),
                    keys.commandStream.toByteArray(),
                    consumerGroup.toByteArray(),
                    "$".toByteArray(),
                    "MKSTREAM".toByteArray(),
                )
                null
            }
        }
    }

    private fun parseEnvelope(payload: String): TurnDaemonCommandEnvelope? =
        try {
            WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload)
        } catch (_: Exception) {
            null
        }
}
