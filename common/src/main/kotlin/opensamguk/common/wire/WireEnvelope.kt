package opensamguk.common.wire

import kotlinx.serialization.Serializable

/**
 * Wire envelopes mirroring `redisCommandStream.ts` (TurnDaemonCommandEnvelope /
 * TurnDaemonEventEnvelope). Each Redis Streams message carries a single `payload`
 * JSON field holding the encoded envelope.
 */
@Serializable
data class TurnDaemonCommandEnvelope(val requestId: String, val sentAt: String, val command: TurnDaemonCommand)

@Serializable
data class TurnDaemonEventEnvelope(val requestId: String? = null, val sentAt: String, val event: TurnDaemonEvent)

const val WIRE_PAYLOAD_FIELD: String = "payload"

fun encodeCommandPayload(env: TurnDaemonCommandEnvelope): String =
    WireJson.encodeToString(TurnDaemonCommandEnvelope.serializer(), env)

fun decodeCommandEnvelope(payload: String): TurnDaemonCommandEnvelope =
    WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(), payload)
