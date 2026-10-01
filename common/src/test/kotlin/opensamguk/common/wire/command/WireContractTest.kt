package opensamguk.common.wire.command

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import opensamguk.common.wire.WireJson
import opensamguk.common.world.WorldId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals

class WireContractTest {
    @Test
    fun `command result envelope round-trips its explicit schema world correlation and payload`() {
        // Given
        val original = commandResultEnvelope(worldId = WorldId(101))

        // When
        val encoded = encodeCommandResultEnvelope(original)
        val decoded = decodeCommandResultEnvelope(encoded)

        // Then
        assertEquals(original, decoded)
        assertEquals(
            1,
            WireJson.parseToJsonElement(encoded).jsonObject.getValue("schemaVersion").jsonPrimitive.int,
        )
    }

    @Test
    fun `turn event envelope round-trips its explicit schema world turn identity and payload`() {
        // Given
        val original = turnEventEnvelope(worldId = WorldId(202))

        // When
        val encoded = encodeTurnEventEnvelope(original)
        val decoded = decodeTurnEventEnvelope(encoded)

        // Then
        assertEquals(original, decoded)
        assertEquals(
            1,
            WireJson.parseToJsonElement(encoded).jsonObject.getValue("schemaVersion").jsonPrimitive.int,
        )
    }

    @Test
    fun `command result decoder rejects an unsupported schema version`() {
        // Given
        val encoded = encodeCommandResultEnvelope(commandResultEnvelope(worldId = WorldId(101)))
        val unsupported = withSchemaVersion(encoded, 2)

        // When / Then
        assertFails { decodeCommandResultEnvelope(unsupported) }
    }

    @Test
    fun `command result decoder rejects a missing schema version`() {
        // Given
        val encoded = encodeCommandResultEnvelope(commandResultEnvelope(worldId = WorldId(101)))
        val missingVersion = withoutSchemaVersion(encoded)

        // When / Then
        assertFails { decodeCommandResultEnvelope(missingVersion) }
    }

    @Test
    fun `turn event decoder rejects an unsupported schema version`() {
        // Given
        val encoded = encodeTurnEventEnvelope(turnEventEnvelope(worldId = WorldId(202)))
        val unsupported = withSchemaVersion(encoded, 2)

        // When / Then
        assertFails { decodeTurnEventEnvelope(unsupported) }
    }

    @Test
    fun `turn event decoder rejects a missing schema version`() {
        // Given
        val encoded = encodeTurnEventEnvelope(turnEventEnvelope(worldId = WorldId(202)))
        val missingVersion = withoutSchemaVersion(encoded)

        // When / Then
        assertFails { decodeTurnEventEnvelope(missingVersion) }
    }

    @Test
    fun `decoded v2 envelopes preserve distinct canonical world identifiers`() {
        // Given
        val commandWorldId = WorldId(101)
        val turnWorldId = WorldId(202)

        // When
        val decodedCommand = decodeCommandResultEnvelope(
            encodeCommandResultEnvelope(commandResultEnvelope(worldId = commandWorldId)),
        )
        val decodedTurn = decodeTurnEventEnvelope(
            encodeTurnEventEnvelope(turnEventEnvelope(worldId = turnWorldId)),
        )

        // Then
        assertEquals(commandWorldId, decodedCommand.worldId)
        assertEquals(turnWorldId, decodedTurn.worldId)
        assertNotEquals(decodedCommand.worldId, decodedTurn.worldId)
    }

    private fun commandResultEnvelope(worldId: WorldId): CommandResultEnvelope =
        CommandResultEnvelope(
            worldId = worldId,
            requestId = "request-101",
            eventId = "command-result:${worldId.value}:request-101:1",
            sentAt = "0200-01-01T00:00:00Z",
            committedWorldVersion = 31,
            resultType = "executionRejected",
            ok = false,
            payload = buildJsonObject {
                put("reason", "조건 불충족")
                put("turnIdx", 0)
            },
        )

    private fun turnEventEnvelope(worldId: WorldId): TurnEventEnvelope =
        TurnEventEnvelope(
            worldId = worldId,
            eventId = "turn-completed:${worldId.value}:31",
            turnId = "turn-${worldId.value}-31",
            occurredAt = "0200-01-01T00:00:00Z",
            committedWorldVersion = 31,
            eventType = "turnCompleted",
            payload = buildJsonObject {
                put("year", 200)
                put("month", 1)
                put("phase", 1)
            },
        )

    private fun withSchemaVersion(encoded: String, schemaVersion: Int): String {
        val source = WireJson.parseToJsonElement(encoded).jsonObject
        val versioned = JsonObject(source + ("schemaVersion" to JsonPrimitive(schemaVersion)))
        return WireJson.encodeToString(JsonObject.serializer(), versioned)
    }

    private fun withoutSchemaVersion(encoded: String): String {
        val source = WireJson.parseToJsonElement(encoded).jsonObject
        val unversioned = JsonObject(source - "schemaVersion")
        return WireJson.encodeToString(JsonObject.serializer(), unversioned)
    }
}
