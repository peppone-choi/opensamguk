package opensamguk.logic.auction

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.jsonDecode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class NeutralAuctionRegistrarTest {

    private class ScriptedRng(
        private val bools: ArrayDeque<Boolean>,
        private val ints: ArrayDeque<Int>,
    ) : RandUtil(LiteHashDrbg("neutral-auction-test")) {
        val calls = mutableListOf<String>()

        override fun nextBool(prob: Double): Boolean {
            calls += "nextBool($prob)"
            return bools.removeFirst()
        }

        override fun nextRangeInt(minInclusive: Int, maxInclusive: Int): Int {
            calls += "nextRangeInt($minInclusive,$maxInclusive)"
            return ints.removeFirst()
        }
    }

    @Test
    fun `registerNeutralAuctions opens buyRice then sellRice with PHP average clamp rounding and draw order`() {
        val rng = ScriptedRng(
            bools = ArrayDeque(listOf(true, true)),
            ints = ArrayDeque(listOf(3, 8, 2, 5)),
        )

        val result = registerNeutralAuctions(
            avgGold = 30_000.0,
            avgRice = 8_000.0,
            neutralBuyRiceCount = 0,
            neutralSellRiceCount = 0,
            rng = rng,
            now = Instant.parse("0200-04-01T00:00:00Z"),
            turnTermMinutes = 60,
        )

        assertEquals(
            listOf(
                "nextBool(0.2)",
                "nextRangeInt(1,5)",
                "nextRangeInt(3,12)",
                "nextBool(0.2)",
                "nextRangeInt(1,5)",
                "nextRangeInt(3,12)",
            ),
            rng.calls,
        )
        assertEquals(2, result.opened.size)

        val buy = result.opened[0].info
        assertEquals(AuctionType.BUY_RICE, buy.type)
        assertEquals(0, buy.hostGeneralId)
        assertEquals(ResourceType.GOLD, buy.reqResource)
        assertEquals("1200", buy.target)
        assertEquals("0200-04-01T08:00:00Z", buy.closeDate)
        val buyDetail = jsonDecode(buy.detail.toJson())
        assertEquals("쌀 1200 경매", buyDetail["title"])
        assertEquals("상인", buyDetail["hostName"])
        assertEquals(1200, (buyDetail["amount"] as Number).toInt())
        assertEquals(1440, (buyDetail["startBidAmount"] as Number).toInt())
        assertEquals(2400, (buyDetail["finishBidAmount"] as Number).toInt())

        val sell = result.opened[1].info
        assertEquals(AuctionType.SELL_RICE, sell.type)
        assertEquals(ResourceType.RICE, sell.reqResource)
        assertEquals("2000", sell.target)
        assertEquals("0200-04-01T05:00:00Z", sell.closeDate)
        val sellDetail = jsonDecode(sell.detail.toJson())
        assertEquals("금 2000 경매", sellDetail["title"])
        assertEquals("상인", sellDetail["hostName"])
        assertEquals(2000, (sellDetail["amount"] as Number).toInt())
        assertEquals(1600, (sellDetail["startBidAmount"] as Number).toInt())
        assertEquals(4000, (sellDetail["finishBidAmount"] as Number).toInt())
    }

    @Test
    fun `registerNeutralAuctions consumes only gate bools when both gates miss`() {
        val rng = ScriptedRng(
            bools = ArrayDeque(listOf(false, false)),
            ints = ArrayDeque(),
        )

        val result = registerNeutralAuctions(
            avgGold = null,
            avgRice = null,
            neutralBuyRiceCount = 2,
            neutralSellRiceCount = 3,
            rng = rng,
            now = Instant.parse("0200-04-01T00:00:00Z"),
            turnTermMinutes = 60,
        )

        assertEquals(emptyList(), result.opened)
        assertEquals(listOf("nextBool(${1.0 / 7.0})", "nextBool(0.125)"), rng.calls)
    }
}
