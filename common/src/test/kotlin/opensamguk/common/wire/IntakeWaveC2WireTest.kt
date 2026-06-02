package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * F4 Wave C2 (slice A) wire round-trip — the new single-actor intake command + result variants.
 *
 * Confirms (a) each new [TurnDaemonCommand] variant encodes/decodes through the union discriminator,
 * and (b) the [TurnDaemonCommandResultSerializer] `(type, ok)` selector routes the new types:
 * the 8 nation-finance/enroll types collapse to [NationSettingResult]; each inheritance reset splits
 * Ok/Fail.
 */
class IntakeWaveC2WireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand {
        val raw = WireJson.encodeToString(TurnDaemonCommand.serializer(), c)
        return WireJson.decodeFromString(TurnDaemonCommand.serializer(), raw)
    }

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult {
        val raw = WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r)
        return WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), raw)
    }

    @Test
    fun `finance setter + enroll + inherit reset commands round-trip`() {
        assertEquals(
            TurnDaemonCommand.SetNotice(generalId = 10, msg = "공지"),
            cmdRoundTrip(TurnDaemonCommand.SetNotice(generalId = 10, msg = "공지")),
        )
        assertEquals(
            TurnDaemonCommand.SetRate(generalId = 10, amount = 25),
            cmdRoundTrip(TurnDaemonCommand.SetRate(generalId = 10, amount = 25)),
        )
        assertEquals(
            TurnDaemonCommand.SetBlockWar(generalId = 10, value = true),
            cmdRoundTrip(TurnDaemonCommand.SetBlockWar(generalId = 10, value = true)),
        )
        assertEquals(
            TurnDaemonCommand.TournamentEnroll(generalId = 10, value = 1),
            cmdRoundTrip(TurnDaemonCommand.TournamentEnroll(generalId = 10, value = 1)),
        )
        assertEquals(
            TurnDaemonCommand.InheritResetTurnTime(generalId = 10),
            cmdRoundTrip(TurnDaemonCommand.InheritResetTurnTime(generalId = 10)),
        )
        // the inherit nextSpecial arg rides `specialWar` (NOT `type`, the union discriminator).
        val next = TurnDaemonCommand.InheritSetNextSpecialWar(generalId = 10, specialWar = "che_보병")
        assertEquals(next, cmdRoundTrip(next))
    }

    @Test
    fun `nation-setting result types collapse to NationSettingResult on both ok and fail`() {
        val ok = NationSettingResult(type = "setRate", ok = true, generalId = 10, nationId = 1)
        val rok = resRoundTrip(ok)
        assertIs<NationSettingResult>(rok)
        assertEquals(ok, rok)

        val fail = NationSettingResult(type = "setBlockWar", ok = false, generalId = 10, nationId = 1, reason = "잔여 횟수가 부족합니다.")
        val rfail = resRoundTrip(fail)
        assertIs<NationSettingResult>(rfail)
        assertEquals(fail, rfail)

        val enroll = NationSettingResult(type = "tournamentEnroll", ok = true, generalId = 10, nationId = 1)
        assertIs<NationSettingResult>(resRoundTrip(enroll))
    }

    @Test
    fun `inherit reset result types split Ok and Fail on the ok discriminator`() {
        assertIs<InheritResetTurnTimeOk>(resRoundTrip(InheritResetTurnTimeOk(generalId = 10, spent = 1000)))
        assertIs<InheritResetTurnTimeFail>(resRoundTrip(InheritResetTurnTimeFail(generalId = 10, reason = "부족")))
        assertIs<InheritResetSpecialWarOk>(resRoundTrip(InheritResetSpecialWarOk(generalId = 10, spent = 2000)))
        assertIs<InheritSetNextSpecialWarOk>(resRoundTrip(InheritSetNextSpecialWarOk(generalId = 10, spent = 4000)))
        assertIs<InheritSetNextSpecialWarFail>(resRoundTrip(InheritSetNextSpecialWarFail(generalId = 10, reason = "이미 예약한 특기가 있습니다.")))
    }
}
