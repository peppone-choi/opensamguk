package opensamguk.common.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TurnDaemonCommandWireTest {
    private fun loadArray(resource: String): JsonArray {
        val text = requireNotNull(this::class.java.getResource(resource)) { "missing $resource" }.readText()
        return Json.parseToJsonElement(text) as JsonArray
    }

    private val validCorpus = loadArray("/golden/wire/wire_commands_valid.json")
    private val malformedCorpus = loadArray("/golden/wire/wire_commands_malformed.json")

    @Test
    fun `every valid command deserializes and re-serializes to the same JSON tree`() {
        for (element in validCorpus) {
            val original = element as JsonObject
            val raw = Json.encodeToString(JsonObject.serializer(), original)
            val command = WireJson.decodeFromString(TurnDaemonCommand.serializer(), raw)
            val reEncoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), command)
            val reTree = WireJson.parseToJsonElement(reEncoded)
            assertEquals(original, reTree, "round-trip mismatch for type=${original["type"]?.jsonPrimitive?.content}")
        }
    }

    @Test
    fun `every malformed command is rejected`() {
        for (element in malformedCorpus) {
            val raw = Json.encodeToString(JsonObject.serializer(), element as JsonObject)
            assertFailsWith<Exception>("expected rejection for $raw") {
                WireJson.decodeFromString(TurnDaemonCommand.serializer(), raw)
            }
        }
    }

    @Test
    fun `all command type discriminators are covered by the corpus`() {
        val expected = setOf(
            "run", "pause", "resume", "shutdown", "getStatus", "troopJoin", "troopExit",
            "dieOnPrestart", "buildNationCandidate", "instantRetreat", "vacation", "setMySetting",
            "dropItem", "auctionFinalize", "changePermission", "kick", "appoint", "tournamentRefund",
            "tournamentBettingPayout", "tournamentReward", "voteReward", "setNationMeta",
            "adjustGeneralResources", "adjustGeneralMeta", "tournamentMatchResult", "patchGeneral",
            "auctionBid",
        )
        val seen = validCorpus.map { (it as JsonObject)["type"]!!.jsonPrimitive.content }.toSet()
        assertEquals(expected, seen, "valid corpus must cover exactly the command union")
        assertEquals(27, validCorpus.size, "corpus size is locked")
        assertTrue(seen.size == validCorpus.size, "no duplicate type keys in corpus")
    }
}
