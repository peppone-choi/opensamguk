package opensamguk.logic.auction

import kotlin.math.max

/**
 * The pure-logic substrate of PHP `Auction` (T0.7 foundation). The DB-bound + wall-clock parts of the
 * PHP base (getHighestBid SELECT, openAuction/applyDB INSERT/UPDATE, `new DateTimeImmutable()`) are
 * split: the resolver-facing DETERMINISTIC pieces live here (the close-date interval arithmetic, the
 * forward/reverse highest-bid selection over an injected bid list, the `_bid` validation-order guards,
 * `setHostAsNeutral`); the actual row reads/writes + the wall-clock NOW are the engine auction flush
 * channel + an injected clock (the open_date/close_date wall-clock fields are QUARANTINED — research
 * §11; the close is modeled as a game-tick predicate `game-time >= close_date`).
 *
 * Resource auctions (B1) and the unique-item auction (B2) both extend the SAME base — B1 lands first
 * (it exercises the base fully), B2 second; neither co-widens this base (any widening belongs here).
 */
abstract class AuctionBase {

    companion object {
        const val COEFF_AUCTION_CLOSE_MINUTES = 24.0
        const val COEFF_EXTENSION_MINUTES_PER_BID = 1.0 / 6.0
        const val COEFF_EXTENSION_MINUTES_LIMIT_BY_BID = 0.5
        const val COEFF_EXTENSION_MINUTES_BY_EXTENSION_QUERY = 1.0
        const val MIN_AUCTION_CLOSE_MINUTES = 30.0
        const val MIN_EXTENSION_MINUTES_PER_BID = 1.0
        const val MIN_EXTENSION_MINUTES_LIMIT_BY_BID = 5.0
        const val MIN_EXTENSION_MINUTES_BY_EXTENSION_QUERY = 5.0

        /**
         * The per-bid close-date extension in SECONDS: `max(MIN_EXTENSION_MINUTES_PER_BID,
         * turnterm * COEFF_EXTENSION_MINUTES_PER_BID) * 60`. (PHP `_bid`/`extendLatestBidCloseDate`.)
         * Modeled as a relative second offset (the absolute wall-clock instant is quarantined).
         */
        fun perBidExtensionSeconds(turnTermMinutes: Double): Long =
            (max(MIN_EXTENSION_MINUTES_PER_BID, turnTermMinutes * COEFF_EXTENSION_MINUTES_PER_BID) * 60).toLong()

        /** The tryFinish extension-query offset in SECONDS (PHP `tryFinish`). */
        fun extensionQuerySeconds(turnTermMinutes: Double): Long =
            (max(MIN_EXTENSION_MINUTES_BY_EXTENSION_QUERY, turnTermMinutes * COEFF_EXTENSION_MINUTES_BY_EXTENSION_QUERY) * 60).toLong()

        /** The default auction-open close offset in SECONDS (PHP `COEFF_AUCTION_CLOSE_MINUTES`). */
        fun closeSeconds(turnTermMinutes: Double): Long =
            (max(MIN_AUCTION_CLOSE_MINUTES, turnTermMinutes * COEFF_AUCTION_CLOSE_MINUTES) * 60).toLong()
    }

    /** The auction info this instance operates on (the resolver mutates `detail`/`closeDate`/`finished`). */
    abstract val info: AuctionInfo

    /**
     * The current highest bid over [bids] (PHP `getHighestBid` — forward auctions take the MAX amount,
     * reverse auctions the MIN, LIMIT 1). Returns null when there are no bids. Ties resolve to the
     * FIRST in the supplied order (the caller supplies the rows in the DB scan order so the LIMIT-1
     * pick is faithful).
     */
    fun highestBid(bids: List<AuctionBidItem>): AuctionBidItem? {
        if (bids.isEmpty()) return null
        val reverse = info.detail.isReverse == true
        return if (reverse) bids.minByOrNull { it.amount } else bids.maxByOrNull { it.amount }
    }

    /** My previous bid (PHP `getMyPrevBid`): same forward/reverse extreme, filtered to [generalId]. */
    fun myPrevBid(bids: List<AuctionBidItem>, generalId: Int): AuctionBidItem? =
        highestBid(bids.filter { it.generalId == generalId })

    /**
     * The deterministic `_bid` validation guards (PHP `_bid`), returning a non-null Korean error string
     * on rejection or null on success-so-far. This is the DRAW-FREE precondition gate — the actual
     * resource debit / bid INSERT / refund / close-date extend are emitted by the leaf families via the
     * engine flush channel. `now`/`closeDate`/`openDate` are passed as comparable game-time longs (the
     * wall-clock instants are quarantined; the resolver supplies the injected game-time).
     */
    fun validateBidGuards(
        amount: Int,
        nowGameTime: Long,
        openGameTime: Long,
        closeGameTime: Long,
        highestBid: AuctionBidItem?,
    ): String? {
        if (info.finished) return "경매가 이미 끝났습니다."
        if (closeGameTime < nowGameTime) return "경매가 이미 끝났습니다."
        if (openGameTime > nowGameTime) return "경매가 아직 시작되지 않았습니다."

        val reverse = info.detail.isReverse == true
        val finishBid = info.detail.finishBidAmount
        if (!reverse) {
            if (finishBid != null && finishBid < amount) return "즉시판매가보다 높을 수 없습니다."
            if (highestBid != null && amount <= highestBid.amount) return "현재입찰가보다 높게 입찰해야 합니다."
        } else {
            if (finishBid != null && finishBid > amount) return "즉시판매가보다 낮을 수 없습니다."
            if (highestBid != null && amount >= highestBid.amount) return "현재입찰가보다 낮게 입찰해야 합니다."
        }
        return null
    }

    /**
     * The inheritance-point min-raise guard (PHP `bidInheritPoint`): `amount >= highest*1.01` AND
     * `amount >= highest + 10`. Returns the Korean error or null.
     */
    fun validateInheritPointRaise(amount: Int, highestBid: AuctionBidItem?): String? {
        if (highestBid == null) return null
        if (amount < highestBid.amount * 1.01) return "현재입찰가보다 1% 높게 입찰해야 합니다."
        if (amount < highestBid.amount + 10) return "현재입찰가보다 10 포인트 높게 입찰해야 합니다."
        return null
    }

    /** `setHostAsNeutral` — reveal the (상인) host on a unique-item auction (the dummy host marker). */
    fun isHostDummy(): Boolean = DummyGeneral.isDummyHost(info.hostGeneralId)

    // --- abstract lifecycle hooks (the leaf families implement) ----------------------------------

    /** PHP `bid` — the type-specific bid (resource debit / inherit-point debit). */
    abstract fun bid(amount: Int, tryExtendCloseDate: Boolean): String?

    /** PHP `rollbackAuction` — the 유찰 path (host refund). */
    abstract fun rollbackAuction()

    /** PHP `finishAuction` — the 성사 path (two-sided swap / equip). Returns a fail reason or null. */
    abstract fun finishAuction(highestBid: AuctionBidItem, bidderId: Int): String?
}
