package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.SerializationException

class ImmediateInputWireTest {
    @Test fun `court dispatch and reply round trip through sealed command`() {
        for ((input, args) in listOf("court.dispatch" to "{\"targetGeneralId\":2,\"countyId\":10}",
            "court.dispatchReply" to "{\"dispatchId\":\"req-1\",\"accept\":false}")) {
            val command = TurnDaemonCommand.ImmediateInput("req-1", 1, 42, input, args)
            val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), command)
            assertTrue(encoded.contains("\"type\":\"immediateInput\""))
            assertEquals(command, WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded))
        }
    }

    @Test fun `missing owner cannot silently decode`() {
        assertFailsWith<SerializationException> {
            WireJson.decodeFromString(TurnDaemonCommand.serializer(),
                """{"type":"immediateInput","requestId":"req-1","generalId":1,"inputId":"court.dispatch","argJson":"{}"}""")
        }
    }
}
