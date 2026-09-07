package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Phase 4X-A — 가신·부곡 6 명령과 [RetainerActionResult] 의 wire 왕복(직렬화기 `else -> throw` 가 실패점, spec N8). */
class RetainerIntakeWireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand =
        WireJson.decodeFromString(TurnDaemonCommand.serializer(), WireJson.encodeToString(TurnDaemonCommand.serializer(), c))

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult =
        WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r))

    @Test
    fun `six retainer commands round-trip and keep nullable args`() {
        val cmds = listOf(
            TurnDaemonCommand.RetainerPledge(generalId = 10, name = "홍길동", relation = "lieutenant", role = "GUARD"),
            TurnDaemonCommand.RetainerPledge(generalId = 10),
            TurnDaemonCommand.RetainerRelease(generalId = 10, retainerId = 3),
            TurnDaemonCommand.RetainerTask(generalId = 10, retainerId = 3, task = "train"),
            TurnDaemonCommand.BugokForm(generalId = 10, troops = 300, rice = 900),
            TurnDaemonCommand.BugokDisband(generalId = 10, bugokId = 2),
            TurnDaemonCommand.BugokAssignCommander(generalId = 10, bugokId = 2, retainerId = null),
        )
        for (c in cmds) assertEquals(c, cmdRoundTrip(c))
        assertEquals(null, (cmdRoundTrip(cmds[1]) as TurnDaemonCommand.RetainerPledge).name)
    }

    @Test
    fun `every retainer result code routes to RetainerActionResult on ok and fail`() {
        for (type in RETAINER_ACTION_TYPES) {
            val ok = RetainerActionResult(type = type, ok = true, generalId = 10, id = 7)
            val rok = resRoundTrip(ok)
            assertIs<RetainerActionResult>(rok)
            assertEquals(ok, rok)
            val fail = RetainerActionResult(type = type, ok = false, generalId = 10, reason = "자금이 부족합니다.")
            assertEquals(fail, resRoundTrip(fail))
        }
        assertEquals(6, RETAINER_ACTION_TYPES.size)
    }
}
