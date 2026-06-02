package opensamguk.logic.auction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuctionBaseTest {

    private fun base(detail: AuctionInfoDetail, finished: Boolean = false): AuctionBase {
        val auctionInfo = AuctionInfo(
            id = 1, type = AuctionType.BUY_RICE, finished = finished, target = null,
            hostGeneralId = 0, reqResource = ResourceType.GOLD,
            openDate = "0", closeDate = "100", detail = detail,
        )
        return object : AuctionBase() {
            override val info = auctionInfo
            override fun bid(amount: Int, tryExtendCloseDate: Boolean): String? = null
            override fun rollbackAuction() {}
            override fun finishAuction(highestBid: AuctionBidItem, bidderId: Int): String? = null
        }
    }

    private fun bid(generalId: Int, amount: Int, no: Int) = AuctionBidItem(
        no = no, auctionId = 1, owner = null, generalId = generalId, amount = amount,
        date = "0", aux = AuctionBidItemData(generalName = "g$generalId"),
    )

    @Test
    fun `close-date arithmetic uses max(MIN, turnterm*COEFF)*60s`() {
        // per-bid: max(1, turnterm*(1/6))*60. turnterm=60 -> max(1,10)*60 = 600s.
        assertEquals(600L, AuctionBase.perBidExtensionSeconds(60.0))
        // turnterm=3 -> max(1, 0.5)*60 = 60s.
        assertEquals(60L, AuctionBase.perBidExtensionSeconds(3.0))
        // close: max(30, turnterm*24)*60. turnterm=60 -> max(30,1440)*60 = 86400s.
        assertEquals(86400L, AuctionBase.closeSeconds(60.0))
        // turnterm=1 -> max(30,24)*60 = 1800s.
        assertEquals(1800L, AuctionBase.closeSeconds(1.0))
        // extension-query: max(5, turnterm*1)*60. turnterm=10 -> 600s; turnterm=2 -> 300s.
        assertEquals(600L, AuctionBase.extensionQuerySeconds(10.0))
        assertEquals(300L, AuctionBase.extensionQuerySeconds(2.0))
    }

    @Test
    fun `highestBid forward takes MAX, reverse takes MIN`() {
        val bids = listOf(bid(1, 100, 1), bid(2, 300, 2), bid(3, 200, 3))
        val fwd = base(AuctionInfoDetail("t", "h", 10, isReverse = null, startBidAmount = 50))
        assertEquals(2, fwd.highestBid(bids)!!.no, "forward → MAX amount")
        val rev = base(AuctionInfoDetail("t", "h", 10, isReverse = true, startBidAmount = 500))
        assertEquals(1, rev.highestBid(bids)!!.no, "reverse → MIN amount")
        assertNull(fwd.highestBid(emptyList()))
    }

    @Test
    fun `validateBidGuards enforces the PHP ordering (finished, closed, not-started, finishBid, raise)`() {
        val det = AuctionInfoDetail("t", "h", 10, isReverse = null, startBidAmount = 50, finishBidAmount = 1000)
        val b = base(det)
        // finished
        assertEquals("경매가 이미 끝났습니다.", base(det, finished = true).validateBidGuards(100, 50, 0, 100, null))
        // closed (now > close)
        assertEquals("경매가 이미 끝났습니다.", b.validateBidGuards(100, 200, 0, 100, null))
        // not started (open > now)
        assertEquals("경매가 아직 시작되지 않았습니다.", b.validateBidGuards(100, 5, 10, 100, null))
        // above finishBid
        assertEquals("즉시판매가보다 높을 수 없습니다.", b.validateBidGuards(1001, 50, 0, 100, null))
        // not higher than highest
        assertEquals("현재입찰가보다 높게 입찰해야 합니다.", b.validateBidGuards(200, 50, 0, 100, bid(9, 200, 9)))
        // valid
        assertNull(b.validateBidGuards(300, 50, 0, 100, bid(9, 200, 9)))
    }

    @Test
    fun `validateInheritPointRaise enforces 1 percent AND plus-10 minimums`() {
        val b = base(AuctionInfoDetail("t", "h", 10, isReverse = null, startBidAmount = 50))
        val high = bid(9, 1000, 9)
        assertEquals("현재입찰가보다 1% 높게 입찰해야 합니다.", b.validateInheritPointRaise(1005, high)) // < 1010
        // 1009 fails both guards (1009 >= 999*1.01=1008.99 true, 1009 < 999+10=1009 false). Use 500: 505>=505 true, 505<510 true.
        assertEquals("현재입찰가보다 10 포인트 높게 입찰해야 합니다.", b.validateInheritPointRaise(505, bid(9, 500, 9)))
        assertNull(b.validateInheritPointRaise(1010, high))
        assertNull(b.validateInheritPointRaise(50, null), "no highest → no raise guard")
    }

    @Test
    fun `isHostDummy detects the 상인 host_general_id 0`() {
        assertEquals(true, base(AuctionInfoDetail("t", "h", 10, isReverse = null, startBidAmount = 5)).isHostDummy())
    }
}
