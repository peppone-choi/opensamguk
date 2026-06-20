package opensamguk.common.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * 유산 포인트 구매 wire round-trip — BuyHiddenBuff/BuyRandomUnique command + result 변종.
 *
 * (a) 각 [TurnDaemonCommand] 변종이 union 판별자로 인코드/디코드되고, (b)
 * [TurnDaemonCommandResultSerializer]의 `(type, ok)` 셀렉터가 Ok/Fail을 분기하는지 확인한다.
 */
class InheritBuyWireTest {

    private fun cmdRoundTrip(c: TurnDaemonCommand): TurnDaemonCommand {
        val raw = WireJson.encodeToString(TurnDaemonCommand.serializer(), c)
        return WireJson.decodeFromString(TurnDaemonCommand.serializer(), raw)
    }

    private fun resRoundTrip(r: TurnDaemonCommandResult): TurnDaemonCommandResult {
        val raw = WireJson.encodeToString(TurnDaemonCommandResult.serializer(), r)
        return WireJson.decodeFromString(TurnDaemonCommandResult.serializer(), raw)
    }

    @Test
    fun `buy commands round-trip`() {
        assertEquals(
            TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 2),
            cmdRoundTrip(TurnDaemonCommand.BuyHiddenBuff(generalId = 10, buffKey = "warAvoidRatio", level = 2)),
        )
        assertEquals(
            TurnDaemonCommand.BuyRandomUnique(generalId = 10),
            cmdRoundTrip(TurnDaemonCommand.BuyRandomUnique(generalId = 10)),
        )
        assertEquals(
            TurnDaemonCommand.ResetStat(generalId = 10, leadership = 55, strength = 55, intel = 55, inheritBonusStat = listOf(1, 1, 1)),
            cmdRoundTrip(TurnDaemonCommand.ResetStat(generalId = 10, leadership = 55, strength = 55, intel = 55, inheritBonusStat = listOf(1, 1, 1))),
        )
    }

    @Test
    fun `buy results split Ok and Fail`() {
        assertIs<BuyHiddenBuffOk>(resRoundTrip(BuyHiddenBuffOk(generalId = 10, spent = 400)))
        assertIs<BuyHiddenBuffFail>(resRoundTrip(BuyHiddenBuffFail(generalId = 10, reason = "이미 구입했습니다.")))
        assertIs<BuyRandomUniqueOk>(resRoundTrip(BuyRandomUniqueOk(generalId = 10, spent = 3000)))
        assertIs<BuyRandomUniqueFail>(resRoundTrip(BuyRandomUniqueFail(generalId = 10, reason = "충분한 유산 포인트를 가지고 있지 않습니다.")))
        assertIs<ResetStatOk>(resRoundTrip(ResetStatOk(generalId = 10, spent = 1000, leadership = 56, strength = 56, intel = 56)))
        assertIs<ResetStatFail>(resRoundTrip(ResetStatFail(generalId = 10, reason = "이번 시즌에 이미 능력치를 초기화하셨습니다.")))
    }
}
