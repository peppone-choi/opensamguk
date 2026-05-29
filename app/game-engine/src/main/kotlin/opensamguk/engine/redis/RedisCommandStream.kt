package opensamguk.engine.redis

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonStreamKeys
import opensamguk.common.wire.WIRE_PAYLOAD_FIELD
import opensamguk.common.wire.WireJson
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
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
class RedisCommandStream(
    private val template: StringRedisTemplate,
    profileName: String,
    startId: String = "\$",
) {
    private val keys: TurnDaemonStreamKeys = TurnDaemonStreamKeys.of(profileName)
    private var lastId: String

    init {
        // `'$'` means "only messages newer than the consumer's start point". Spring Data Redis
        // would forward a literal `$` to XREAD, which Redis resolves to the stream tail at *read*
        // time — so any message enqueued between construction and the first read would be skipped.
        // To match the TS `startId ?? '$'` intent (only-new relative to *construction*), resolve
        // `$` to the current stream tail here. An explicit id (e.g. "0") is used verbatim.
        lastId = if (startId == "\$") currentTail() else startId
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

    /**
     * `XREAD BLOCK <blockMs> COUNT 100` from the current cursor. Returns the parsed commands and
     * advances [lastId] to the id of the last record read (even when its payload fails to parse).
     */
    fun readCommands(blockMs: Long): List<TurnDaemonCommand> {
        val options = StreamReadOptions.empty().count(100).block(Duration.ofMillis(blockMs))
        val offset = StreamOffset.create(keys.commandStream, ReadOffset.from(lastId))
        val records: List<MapRecord<String, Any, Any>> =
            template.opsForStream<Any, Any>().read(options, offset) ?: return emptyList()

        val commands = mutableListOf<TurnDaemonCommand>()
        for (record in records) {
            lastId = record.id.value
            val payload = record.value[WIRE_PAYLOAD_FIELD]?.toString() ?: continue
            val envelope = parseEnvelope(payload) ?: continue
            commands.add(envelope.command)
        }
        return commands
    }

    private fun parseEnvelope(payload: String): TurnDaemonCommandEnvelope? =
        try {
            WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload)
        } catch (_: Exception) {
            null
        }
}
