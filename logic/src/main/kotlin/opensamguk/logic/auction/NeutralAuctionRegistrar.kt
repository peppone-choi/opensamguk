package opensamguk.logic.auction

import opensamguk.common.rng.RandUtil
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.valueFit
import java.time.Instant

data class NeutralAuctionOpen(
    val info: AuctionInfo,
    val term: Int,
)

data class NeutralAuctionRegistrationResult(
    val opened: List<NeutralAuctionOpen>,
)

fun registerNeutralAuctions(
    avgGold: Double?,
    avgRice: Double?,
    neutralBuyRiceCount: Int,
    neutralSellRiceCount: Int,
    rng: RandUtil,
    now: Instant,
    turnTermMinutes: Int,
): NeutralAuctionRegistrationResult {
    val clampedGold = valueFit(avgGold ?: 0.0, 1000.0, 20000.0)
    val clampedRice = valueFit(avgRice ?: 0.0, 1000.0, 20000.0)
    val opened = mutableListOf<NeutralAuctionOpen>()

    if (rng.nextBool(1.0 / (neutralBuyRiceCount + 5))) {
        val mul = rng.nextRangeInt(1, 5)
        val amount = phpRound(clampedRice / 20.0 * mul, -1)
        val cost = phpRound(valueFit(clampedGold / 20.0 * 0.9 * mul, amount * 0.8, amount * 1.2), -1)
        val topv = phpRound(amount * 2.0, -1)
        val term = rng.nextRangeInt(3, 12)
        opened += NeutralAuctionOpen(
            info = resourceAuctionInfo(
                type = AuctionType.BUY_RICE,
                bidderResource = ResourceType.GOLD,
                resourceName = "쌀",
                amount = amount,
                term = term,
                startBidAmount = cost,
                finishBidAmount = topv,
                now = now,
                turnTermMinutes = turnTermMinutes,
            ),
            term = term,
        )
    }

    if (rng.nextBool(1.0 / (neutralSellRiceCount + 5))) {
        val mul = rng.nextRangeInt(1, 5)
        val amount = phpRound(clampedGold / 20.0 * mul, -1)
        val cost = phpRound(valueFit(clampedRice / 20.0 * 1.1 * mul, amount * 0.8, amount * 1.2), -1)
        val topv = phpRound(amount * 2.0, -1)
        val term = rng.nextRangeInt(3, 12)
        opened += NeutralAuctionOpen(
            info = resourceAuctionInfo(
                type = AuctionType.SELL_RICE,
                bidderResource = ResourceType.RICE,
                resourceName = "금",
                amount = amount,
                term = term,
                startBidAmount = cost,
                finishBidAmount = topv,
                now = now,
                turnTermMinutes = turnTermMinutes,
            ),
            term = term,
        )
    }

    return NeutralAuctionRegistrationResult(opened)
}

private fun resourceAuctionInfo(
    type: AuctionType,
    bidderResource: ResourceType,
    resourceName: String,
    amount: Int,
    term: Int,
    startBidAmount: Int,
    finishBidAmount: Int,
    now: Instant,
    turnTermMinutes: Int,
): AuctionInfo = AuctionInfo(
    id = null,
    type = type,
    finished = false,
    target = amount.toString(),
    hostGeneralId = 0,
    reqResource = bidderResource,
    openDate = now.toString(),
    closeDate = now.plusSeconds(term.toLong() * turnTermMinutes * 60L).toString(),
    detail = AuctionInfoDetail(
        title = "$resourceName $amount 경매",
        hostName = "상인",
        amount = amount,
        isReverse = false,
        startBidAmount = startBidAmount,
        finishBidAmount = finishBidAmount,
        remainCloseDateExtensionCnt = null,
        availableLatestBidCloseDate = null,
    ),
)
