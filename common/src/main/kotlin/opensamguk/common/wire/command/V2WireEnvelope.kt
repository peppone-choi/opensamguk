package opensamguk.common.wire.v2

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId

const val V2_COMMAND_RESULT_SCHEMA_VERSION: Int = 1
const val V2_TURN_EVENT_SCHEMA_VERSION: Int = 1

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class V2CommandResultEnvelope(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = V2_COMMAND_RESULT_SCHEMA_VERSION,
    val worldId: WorldId,
    val requestId: String,
    val eventId: String,
    val sentAt: String,
    val committedWorldVersion: Long,
    val resultType: String,
    val ok: Boolean,
    val payload: JsonObject,
) {
    init {
        requireCurrentCommandResultSchemaVersion(schemaVersion)
    }
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class V2TurnEventEnvelope(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = V2_TURN_EVENT_SCHEMA_VERSION,
    val worldId: WorldId,
    val eventId: String,
    val turnId: String,
    val occurredAt: String,
    val committedWorldVersion: Long,
    val eventType: String,
    val payload: JsonObject,
) {
    init {
        requireCurrentTurnEventSchemaVersion(schemaVersion)
    }
}

fun encodeV2CommandResultEnvelope(envelope: V2CommandResultEnvelope): String =
    WireJson.encodeToString(V2CommandResultEnvelope.serializer(), envelope)

fun decodeV2CommandResultEnvelope(payload: String): V2CommandResultEnvelope =
    WireJson.decodeFromJsonElement(
        V2CommandResultEnvelope.serializer(),
        parseVersionedEnvelope(payload, "v2 command-result"),
    )

fun encodeV2TurnEventEnvelope(envelope: V2TurnEventEnvelope): String =
    WireJson.encodeToString(V2TurnEventEnvelope.serializer(), envelope)

fun decodeV2TurnEventEnvelope(payload: String): V2TurnEventEnvelope =
    WireJson.decodeFromJsonElement(
        V2TurnEventEnvelope.serializer(),
        parseVersionedEnvelope(payload, "v2 turn-event"),
    )

private fun parseVersionedEnvelope(payload: String, envelopeType: String): JsonObject {
    val envelope = WireJson.parseToJsonElement(payload).jsonObject
    require("schemaVersion" in envelope) {
        "Missing $envelopeType schema version"
    }
    return envelope
}

private fun requireCurrentCommandResultSchemaVersion(schemaVersion: Int) {
    require(schemaVersion == V2_COMMAND_RESULT_SCHEMA_VERSION) {
        "Unsupported v2 command-result schema version: $schemaVersion"
    }
}

private fun requireCurrentTurnEventSchemaVersion(schemaVersion: Int) {
    require(schemaVersion == V2_TURN_EVENT_SCHEMA_VERSION) {
        "Unsupported v2 turn-event schema version: $schemaVersion"
    }
}
