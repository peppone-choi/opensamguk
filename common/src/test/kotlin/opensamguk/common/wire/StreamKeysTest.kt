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

    // W0-4 인테이크 결과 회신 채널 — per-requestId 결과 키. 스트림 키와 동일하게 프로필을
    // verbatim 보간한다(트림 없음). game-api(GET 조회)와 engine(SET 발행)이 같은 키를 쓴다.
    @Test
    fun `command result key interpolates profile and requestId verbatim`() {
        assertEquals(
            "sammo:default:turn-daemon:result:req-1",
            commandResultKey("default", "req-1"),
        )
        assertEquals(
            "sammo:che:scenario_2:turn-daemon:result:0aa6f5b2-6d5f-4cf6-9d3e-111122223333",
            commandResultKey("che:scenario_2", "0aa6f5b2-6d5f-4cf6-9d3e-111122223333"),
        )
    }
}
