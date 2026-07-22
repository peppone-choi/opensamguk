package opensamguk.common.wire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TurnDaemonCommandResultWireTest {
    private fun loadArray(resource: String): JsonArray {
        val text = requireNotNull(this::class.java.getResource(resource)) { "missing $resource" }.readText()
        return Json.parseToJsonElement(text) as JsonArray
    }

    private val corpus = loadArray("/golden/wire/wire_results.json")

    private fun decode(obj: JsonObject): TurnDaemonCommandResult {
        val raw = Json.encodeToString(JsonObject.serializer(), obj)
        return WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), raw)
    }

    @Test
    fun `every result entry round-trips to the same JSON tree`() {
        for (element in corpus) {
            val original = element as JsonObject
            val result = decode(original)
            val reEncoded = WireJson.encodeToString(TurnDaemonCommandResult.serializer(), result)
            val reTree = WireJson.parseToJsonElement(reEncoded)
            assertEquals(
                original,
                reTree,
                "round-trip mismatch type=${original["type"]?.jsonPrimitive?.content} ok=${original["ok"]?.jsonPrimitive?.content}",
            )
        }
    }

    @Test
    fun `dual discriminator selects the correct Ok and Fail concrete class`() {
        val byKey = corpus.associate { obj ->
            obj as JsonObject
            val type = obj["type"]!!.jsonPrimitive.content
            val ok = obj["ok"]!!.jsonPrimitive.content
            "$type:$ok" to decode(obj)
        }

        assertIs<AuctionFinalizeOk>(byKey["auctionFinalize:true"])
        assertIs<AuctionFinalizeFail>(byKey["auctionFinalize:false"])
        assertIs<TroopJoinOk>(byKey["troopJoin:true"])
        assertIs<TroopJoinFail>(byKey["troopJoin:false"])
        assertIs<TroopExitOk>(byKey["troopExit:true"])
        assertIs<TroopExitFail>(byKey["troopExit:false"])
        assertIs<TournamentMatchResultOk>(byKey["tournamentMatchResult:true"])
        assertIs<TournamentMatchResultFail>(byKey["tournamentMatchResult:false"])
        // boolean-ok group collapses to GeneralBoolResult regardless of ok
        assertIs<GeneralBoolResult>(byKey["dieOnPrestart:true"])
        assertIs<GeneralBoolResult>(byKey["appoint:false"])
    }

    @Test
    fun `troopJoin ok-true does not produce the Fail class and Fail carries reason`() {
        val okEntry = corpus.first {
            (it as JsonObject)["type"]!!.jsonPrimitive.content == "troopJoin" &&
                it["ok"]!!.jsonPrimitive.content == "true"
        } as JsonObject
        val failEntry = corpus.first {
            (it as JsonObject)["type"]!!.jsonPrimitive.content == "troopJoin" &&
                it["ok"]!!.jsonPrimitive.content == "false"
        } as JsonObject

        val ok = decode(okEntry)
        assertIs<TroopJoinOk>(ok)
        assertTrue(decode(okEntry) !is TroopJoinFail)

        val fail = decode(failEntry)
        assertIs<TroopJoinFail>(fail)
        assertEquals("troop full", fail.reason)
    }

    @Test
    fun `command lifecycle result round-trips for durable reservation and queue terminals`() {
        val reservation = CommandLifecycleResult(
            type = "reservationAccepted",
            ok = true,
            commandKind = "RESERVED_TURN",
            actionCode = "che_견문",
            generalId = 10,
            turnIdx = 0,
        )
        val queue = CommandLifecycleResult(
            type = "queueMutation",
            ok = true,
            commandKind = "QUEUE_MUTATION",
            actionCode = "pushGeneral",
            generalId = 10,
        )
        val executionApplied = CommandLifecycleResult(
            type = "executionApplied",
            ok = true,
            commandKind = "RESERVED_TURN",
            actionCode = "che_견문",
            generalId = 10,
            turnIdx = 0,
        )
        val executionRejected = CommandLifecycleResult(
            type = "executionRejected",
            ok = false,
            commandKind = "RESERVED_TURN",
            actionCode = "che_견문",
            generalId = 10,
            turnIdx = 0,
            reason = "조건 불충족",
        )

        assertEquals(
            reservation,
            WireJson.decodeFromString(
                TurnDaemonCommandResult.serializer(),
                WireJson.encodeToString(TurnDaemonCommandResult.serializer(), reservation),
            ),
        )
        assertEquals(
            queue,
            WireJson.decodeFromString(
                TurnDaemonCommandResult.serializer(),
                WireJson.encodeToString(TurnDaemonCommandResult.serializer(), queue),
            ),
        )
        assertEquals(
            executionApplied,
            WireJson.decodeFromString(
                TurnDaemonCommandResult.serializer(),
                WireJson.encodeToString(TurnDaemonCommandResult.serializer(), executionApplied),
            ),
        )
        assertEquals(
            executionRejected,
            WireJson.decodeFromString(
                TurnDaemonCommandResult.serializer(),
                WireJson.encodeToString(TurnDaemonCommandResult.serializer(), executionRejected),
            ),
        )
    }
}
