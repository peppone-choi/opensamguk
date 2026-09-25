package opensamguk.common.wire.command

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId

const val COMMAND_RESULT_SCHEMA_VERSION: Int = 1
const val TURN_EVENT_SCHEMA_VERSION: Int = 1

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CommandResultEnvelope(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = COMMAND_RESULT_SCHEMA_VERSION,
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
data class TurnEventEnvelope(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val schemaVersion: Int = TURN_EVENT_SCHEMA_VERSION,
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

fun encodeV2CommandResultEnvelope(envelope: CommandResultEnvelope): String =
    WireJson.encodeToString(CommandResultEnvelope.serializer(), envelope)

fun decodeV2CommandResultEnvelope(payload: String): CommandResultEnvelope =
    WireJson.decodeFromJsonElement(
        CommandResultEnvelope.serializer(),
        parseVersionedEnvelope(payload, "v2 command-result"),
    )

fun encodeV2TurnEventEnvelope(envelope: TurnEventEnvelope): String =
    WireJson.encodeToString(TurnEventEnvelope.serializer(), envelope)

fun decodeV2TurnEventEnvelope(payload: String): TurnEventEnvelope =
    WireJson.decodeFromJsonElement(
        TurnEventEnvelope.serializer(),
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
    require(schemaVersion == COMMAND_RESULT_SCHEMA_VERSION) {
        "Unsupported v2 command-result schema version: $schemaVersion"
    }
}

private fun requireCurrentTurnEventSchemaVersion(schemaVersion: Int) {
    require(schemaVersion == TURN_EVENT_SCHEMA_VERSION) {
        "Unsupported v2 turn-event schema version: $schemaVersion"
    }
}
