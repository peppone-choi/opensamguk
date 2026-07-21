package opensamguk.common.wire

import opensamguk.common.world.WorldId
import kotlin.test.Test
import kotlin.test.assertEquals

class StreamKeysTest {
    private val w1 = WorldId(1)
    private val w42 = WorldId(42)

    @Test
    fun `default profile builds world-scoped command + event stream keys`() {
        val keys = TurnDaemonStreamKeys.of("default", w1)
        assertEquals("sammo:default:w1:turn-daemon:commands", keys.commandStream)
        assertEquals("sammo:default:w1:turn-daemon:events", keys.eventStream)
    }

    @Test
    fun `command and event builders interpolate profile verbatim and include world`() {
        val keys = TurnDaemonStreamKeys.of("che:scenario_2", w42)
        assertEquals("sammo:che:scenario_2:w42:turn-daemon:commands", keys.commandStream)
        assertEquals("sammo:che:scenario_2:w42:turn-daemon:events", keys.eventStream)
    }

    @Test
    fun `two worlds with same profile never share stream keys`() {
        val a = TurnDaemonStreamKeys.of("default", WorldId(1))
        val b = TurnDaemonStreamKeys.of("default", WorldId(2))
        assertEquals("sammo:default:w1:turn-daemon:commands", a.commandStream)
        assertEquals("sammo:default:w2:turn-daemon:commands", b.commandStream)
    }

    @Test
    fun `realtime channel trims, defaults blank to unknown, and scopes world`() {
        assertEquals("sammo:default:w1:realtime:events", gameEventChannel("default", w1))
        assertEquals("sammo:che:scenario_2:w42:realtime:events", gameEventChannel("  che:scenario_2  ", w42))
        assertEquals("sammo:unknown:w1:realtime:events", gameEventChannel("   ", w1))
    }

    @Test
    fun `command result key interpolates profile, world, and requestId`() {
        assertEquals(
            "sammo:default:w1:turn-daemon:result:req-1",
            commandResultKey("default", w1, "req-1"),
        )
        assertEquals(
            "sammo:che:scenario_2:w42:turn-daemon:result:0aa6f5b2-6d5f-4cf6-9d3e-111122223333",
            commandResultKey("che:scenario_2", w42, "0aa6f5b2-6d5f-4cf6-9d3e-111122223333"),
        )
    }
}
