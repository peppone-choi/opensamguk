package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamKeysTest {
    @Test
    fun `default profile builds exact command + event stream keys`() {
        val keys = TurnDaemonStreamKeys.of("default")
        assertEquals("sammo:default:turn-daemon:commands", keys.commandStream)
        assertEquals("sammo:default:turn-daemon:events", keys.eventStream)
    }

    @Test
    fun `command and event builders interpolate profile verbatim (no trim)`() {
        val keys = TurnDaemonStreamKeys.of("che:scenario_2")
        assertEquals("sammo:che:scenario_2:turn-daemon:commands", keys.commandStream)
        assertEquals("sammo:che:scenario_2:turn-daemon:events", keys.eventStream)
    }

    @Test
    fun `realtime channel trims and defaults blank to unknown`() {
        assertEquals("sammo:default:realtime:events", gameEventChannel("default"))
        assertEquals("sammo:che:scenario_2:realtime:events", gameEventChannel("  che:scenario_2  "))
        assertEquals("sammo:unknown:realtime:events", gameEventChannel("   "))
    }
}
