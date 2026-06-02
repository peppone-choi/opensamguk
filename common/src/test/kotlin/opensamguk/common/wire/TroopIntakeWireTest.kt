package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * F4 Wave C2 (slice B) wire round-trip — the troop-intake command + result variants.
 *
 * Confirms (a) each new [TurnDaemonCommand] troop variant encodes/decodes through the union
 * discriminator, and (b) the [TurnDaemonCommandResultSerializer] `(type, ok)` selector routes the
 * three new troop types to the collapsed [TroopActionResult] on both ok and fail (the pre-existing
 * `troopJoin`/`troopExit` keep their dedicated Ok/Fail results).
 */
class TroopIntakeWireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand =
        WireJson.decodeFromString(TurnDaemonCommand.serializer(), WireJson.encodeToString(TurnDaemonCommand.serializer(), c))

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult =
        WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r))

    @Test
    fun `troop intake commands round-trip through the union discriminator`() {
        val new = TurnDaemonCommand.TroopNew(generalId = 7, troopName = "제1군단")
        assertEquals(new, cmdRoundTrip(new))

        val join = TurnDaemonCommand.TroopJoin(generalId = 9, troopId = 7)
        assertEquals(join, cmdRoundTrip(join))

        val exit = TurnDaemonCommand.TroopExit(generalId = 9)
        assertEquals(exit, cmdRoundTrip(exit))

        val kick = TurnDaemonCommand.TroopKick(generalId = 7, troopId = 7, targetGeneralId = 9)
        assertEquals(kick, cmdRoundTrip(kick))

        val setName = TurnDaemonCommand.TroopSetName(generalId = 7, troopId = 7, troopName = "새이름")
        assertEquals(setName, cmdRoundTrip(setName))
    }

    @Test
    fun `troop action result types collapse to TroopActionResult on ok and fail`() {
        val newOk = TroopActionResult(type = "troopNew", ok = true, generalId = 7, troopId = 7)
        val rNewOk = resRoundTrip(newOk)
        assertIs<TroopActionResult>(rNewOk)
        assertEquals(newOk, rNewOk)

        val kickOk = TroopActionResult(type = "troopKick", ok = true, generalId = 7, troopId = 7, targetGeneralId = 9)
        assertEquals(kickOk, resRoundTrip(kickOk))

        val setFail = TroopActionResult(type = "troopSetName", ok = false, generalId = 5, reason = "권한이 부족합니다.")
        val rSetFail = resRoundTrip(setFail)
        assertIs<TroopActionResult>(rSetFail)
        assertEquals(setFail, rSetFail)
    }

    @Test
    fun `pre-existing troop join and exit results keep their dedicated shapes`() {
        assertIs<TroopJoinOk>(resRoundTrip(TroopJoinOk(generalId = 9, troopId = 7)))
        assertIs<TroopJoinFail>(resRoundTrip(TroopJoinFail(generalId = 9, troopId = 7, reason = "부대가 올바르지 않습니다.")))
        assertIs<TroopExitOk>(resRoundTrip(TroopExitOk(generalId = 7, wasLeader = true)))
        assertIs<TroopExitFail>(resRoundTrip(TroopExitFail(generalId = 9, reason = "부대에 소속되어 있지 않습니다.")))
    }
}
