package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HwihaInputResolvedWireTest {
    @Test fun `input resolved round trips inside the existing command result envelope`() {
        val result = CommandLifecycleResult(type = "executionApplied", ok = true, commandKind = "RESERVED_TURN",
            actionCode = "action.farm", generalId = 7,
            inputResolved = InputResolved("action.farm", "GENERAL_ACTION", true,
                effects = listOf("agriculture:20.0", "experience:+10", "dedication:+1")))
        val encoded = WireJson.encodeToString(TurnDaemonCommandResult.serializer(), result)
        assertTrue(encoded.contains("inputResolved"))
        assertEquals(result, WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), encoded))
        val rejected = result.copy(type = "executionRejected", ok = false,
            inputResolved = InputResolved("action.farm", "GENERAL_ACTION", false, "縣 소유 변경"))
        assertEquals(rejected, WireJson.decodeFromString(TurnDaemonCommandResult.serializer(),
            WireJson.encodeToString(TurnDaemonCommandResult.serializer(), rejected)))
    }
}
