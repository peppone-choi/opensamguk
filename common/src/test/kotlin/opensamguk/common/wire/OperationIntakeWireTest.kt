package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Phase 4X-B — 작전 4 명령·boardArticle.operationId·[OperationActionResult] 의 wire 왕복(직렬화기 `else -> throw` 가 실패점). */
class OperationIntakeWireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand =
        WireJson.decodeFromString(TurnDaemonCommand.serializer(), WireJson.encodeToString(TurnDaemonCommand.serializer(), c))

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult =
        WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r))

    @Test
    fun `operation commands and boardArticle operationId round-trip`() {
        val cmds = listOf(
            TurnDaemonCommand.OperationDeclare(generalId = 10, kind = "capture_city", targetCityId = 5, title = "낙양 공략", fallbackText = null, deadlineMonths = 3),
            TurnDaemonCommand.OperationDeclare(generalId = 10),
            TurnDaemonCommand.OperationJoin(generalId = 10, operationId = 1, role = "main", bugokId = null),
            TurnDaemonCommand.OperationLeave(generalId = 10, operationId = 1),
            TurnDaemonCommand.OperationClose(generalId = 10, operationId = 1),
            TurnDaemonCommand.BoardArticle(generalId = 10, title = "t", text = "x", kind = "operation", operationId = 1),
        )
        for (c in cmds) assertEquals(c, cmdRoundTrip(c))
        assertEquals(null, (cmdRoundTrip(TurnDaemonCommand.BoardArticle(generalId = 10, title = "t", text = "x")) as TurnDaemonCommand.BoardArticle).operationId)
    }

    @Test
    fun `every operation result code routes to OperationActionResult on ok and fail`() {
        for (type in OPERATION_ACTION_TYPES) {
            val ok = OperationActionResult(type = type, ok = true, generalId = 10, id = 3)
            val rok = resRoundTrip(ok)
            assertIs<OperationActionResult>(rok)
            assertEquals(ok, rok)
            assertEquals(OperationActionResult(type = type, ok = false, generalId = 10, reason = "작전이 없습니다."), resRoundTrip(OperationActionResult(type = type, ok = false, generalId = 10, reason = "작전이 없습니다.")))
        }
        assertEquals(4, OPERATION_ACTION_TYPES.size)
    }
}
