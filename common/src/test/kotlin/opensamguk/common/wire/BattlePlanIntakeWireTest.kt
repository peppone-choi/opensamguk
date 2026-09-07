package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Phase 4X-C — 출병 계획 3 명령·[BattlePlanActionResult] 의 wire 왕복(직렬화기 `else -> throw` 가 실패점 — 빠지면 턴 루프가 멈춘다). */
class BattlePlanIntakeWireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand =
        WireJson.decodeFromString(TurnDaemonCommand.serializer(), WireJson.encodeToString(TurnDaemonCommand.serializer(), c))

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult =
        WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r))

    @Test
    fun `battle plan commands round-trip including null arguments`() {
        val cmds = listOf(
            TurnDaemonCommand.BattlePlanSave(generalId = 10, targetCityId = 5, stance = "probe", retreatLossPct = 30, retreatMoraleBelow = null),
            TurnDaemonCommand.BattlePlanSave(generalId = 10),
            TurnDaemonCommand.BattlePlanSeal(generalId = 10, planId = 3),
            TurnDaemonCommand.BattlePlanDelete(generalId = 10, planId = null),
        )
        for (c in cmds) assertEquals(c, cmdRoundTrip(c))
    }

    @Test
    fun `every battle plan result code routes to BattlePlanActionResult on ok and fail`() {
        for (type in BATTLE_PLAN_ACTION_TYPES) {
            val ok = BattlePlanActionResult(type = type, ok = true, generalId = 10, id = 3)
            val rok = resRoundTrip(ok)
            assertIs<BattlePlanActionResult>(rok)
            assertEquals(ok, rok)
            val fail = BattlePlanActionResult(type = type, ok = false, generalId = 10, reason = "봉인된 계획입니다.")
            assertEquals(fail, resRoundTrip(fail))
        }
        assertEquals(3, BATTLE_PLAN_ACTION_TYPES.size)
    }
}
