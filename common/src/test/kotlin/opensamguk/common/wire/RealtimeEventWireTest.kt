package opensamguk.common.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RealtimeEventWireTest {
    private fun loadArray(resource: String): JsonArray {
        val text = requireNotNull(this::class.java.getResource(resource)) { "missing $resource" }.readText()
        return Json.parseToJsonElement(text) as JsonArray
    }

    private val realtimeCorpus = loadArray("/golden/wire/wire_realtime.json")

    @Test
    fun `both realtime variants round-trip with national msgType`() {
        for (element in realtimeCorpus) {
            val original = element as JsonObject
            val raw = Json.encodeToString(JsonObject.serializer(), original)
            val event = WireJson.decodeFromString(RealtimeEvent.serializer(), raw)
            val reTree = WireJson.parseToJsonElement(WireJson.encodeToString(RealtimeEvent.serializer(), event))
            assertEquals(original, reTree, "round-trip mismatch type=${original["type"]?.jsonPrimitive?.content}")
        }
        val messageCreated = realtimeCorpus
            .map { WireJson.decodeFromString(RealtimeEvent.serializer(), Json.encodeToString(JsonObject.serializer(), it as JsonObject)) }
            .filterIsInstance<RealtimeEvent.MessageCreated>()
            .single()
        assertEquals(MessageTypeKey.NATIONAL, messageCreated.msgType)
    }

    @Test
    fun `full command envelope round-trips`() {
        val envelope = TurnDaemonCommandEnvelope(
            requestId = "req-1",
            sentAt = "0200-01-01T00:00:00.000Z",
            command = TurnDaemonCommand.AuctionBid(
                requestId = "req-1",
                auctionId = 101,
                generalId = 70,
                amount = 1500,
                tryExtendCloseDate = true,
            ),
        )
        val payload = encodeCommandPayload(envelope)
        val decoded = decodeCommandEnvelope(payload)
        assertEquals(envelope, decoded)
        assertIs<TurnDaemonCommand.AuctionBid>(decoded.command)
    }

    @Test
    fun `event envelope nesting a commandResult Fail round-trips through nested dual discriminator`() {
        val envelope = TurnDaemonEventEnvelope(
            requestId = "req-2",
            sentAt = "0200-01-01T00:00:00.000Z",
            event = TurnDaemonEvent.CommandResult(
                result = TroopJoinFail(generalId = 11, troopId = 7, reason = "troop full"),
            ),
        )
        val raw = WireJson.encodeToString(TurnDaemonEventEnvelope.serializer(), envelope)
        val decoded = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), raw)
        assertEquals(envelope, decoded)
        val inner = (decoded.event as TurnDaemonEvent.CommandResult).result
        val fail = assertIs<TroopJoinFail>(inner)
        assertEquals("troop full", fail.reason)
    }
}
