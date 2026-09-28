package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PresencePulseWireTest {
    @Test
    fun `owner pulse has its own discriminator and round trips without court arguments`() {
        val pulse = TurnDaemonCommand.PresencePulse(generalId = 12, ownerUserId = 19)
        val encoded = WireJson.encodeToString(TurnDaemonCommand.serializer(), pulse)

        assertTrue(encoded.contains("\"type\":\"presencePulse\""))
        assertEquals(pulse, WireJson.decodeFromString(TurnDaemonCommand.serializer(), encoded))
    }
}
