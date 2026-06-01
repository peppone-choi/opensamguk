package opensamguk.logic.auction

/**
 * 입찰 검증 결과 — [AuctionBidValidator.validateBid]의 반환 타입.
 */
sealed class BidValidationResult {
    /** 검증 통과 */
    data object Ok : BidValidationResult()
    /** 검증 실패 — 구체적인 사유를 담음 */
    data class Fail(val reason: String) : BidValidationResult()
}

/**
 * 경매 입찰 검증기 — 순수 함수로 구성된 입찰 유효성 검증 로직.
 *
 * PHP `Auction::validateBid()` / `UniqueAuctionBidder::validateUniqueBid()`의 Kotlin 포팅.
 * 모든 검증은 메모리상의 순수 계산으로, 외부 의존성 없이 호출 가능하다.
 *
 * 검증 규칙 (BUY_RICE / SELL_RICE — 자원 경매):
 * 1. 일반 경매 (!isReverse): 입찰가 > 현재 최고가
 * 2. 역경매 (isReverse): 입찰가 < 현재 최저가
 * 3. 시작가(startBidAmount)가 설정된 경우: 입찰가 >= 시작가
 * 4. 입찰 통화(금/쌀)를 보유하고 있어야 함
 *
 * 검증 규칙 (UNIQUE_ITEM — 유니크 아이템 경매):
 * 1. 입찰가 >= 현재 최고가 * 1.01 (1% 이상 높게)
 * 2. 입찰가 >= 현재 최고가 + 10 (최소 10포인트 이상)
 */
object AuctionBidValidator {

    /**
     * 입찰 유효성을 검증한다.
     *
     * @param auctionType 경매 타입
     * @param bidAmount 제출 입찰가
     * @param currentWinningBidAmount 현재 우승 입찰가 (null = 입찰 없음). 일반 경매에서는 최고가,
     *        역경매에서는 최저가를 의미한다.
     * @param startBidAmount 시작 입찰가 (null = 제한 없음)
     * @param isReverse 역경매 여부
     * @param generalGold 장수의 현재 금 보유량
     * @param generalRice 장수의 현재 쌀 보유량
     * @param previousBidAmount 이전 내 입찰가 (null = 첫 입찰). 차액 입찰 시 자원 검증에 사용.
     * @return [BidValidationResult.Ok] 또는 구체적인 실패 사유를 담은 [BidValidationResult.Fail]
     */
    fun validateBid(
        auctionType: AuctionType,
        bidAmount: Int,
        currentWinningBidAmount: Int?,
        startBidAmount: Int?,
        isReverse: Boolean,
        generalGold: Int,
        generalRice: Int,
        previousBidAmount: Int? = null,
    ): BidValidationResult {
        // 1. 시작가 검증
        if (startBidAmount != null && bidAmount < startBidAmount) {
            return BidValidationResult.Fail("입찰가가 시작가(${startBidAmount})보다 낮습니다.")
        }

        // 2. 최고/최저가 검증 (자원 경매만 — UNIQUE_ITEM은 validateUniqueBid에서 처리)
        if (currentWinningBidAmount != null && auctionType != AuctionType.UNIQUE_ITEM) {
            if (isReverse) {
                // 역경매: 현재 최저가보다 낮아야 함
                if (bidAmount >= currentWinningBidAmount) {
                    return BidValidationResult.Fail(
                        "역경매에서는 현재 최저가(${currentWinningBidAmount})보다 낮게 입찰해야 합니다."
                    )
                }
            } else {
                // 일반 경매: 현재 최고가보다 높아야 함
                if (bidAmount <= currentWinningBidAmount) {
                    return BidValidationResult.Fail(
                        "입찰가가 현재 최고가(${currentWinningBidAmount})보다 높아야 합니다."
                    )
                }
            }
        }

        // 3. 자원 보유량 검증 — 차액 입찰 시 morePoint만큼만 필요
        val morePoint = calculateMorePoint(bidAmount, previousBidAmount)
        val requiredResource = when (auctionType) {
            AuctionType.BUY_RICE -> generalGold // 금으로 쌀 구매 → 입찰 통화 = 금
            AuctionType.SELL_RICE -> generalRice // 쌀로 금 구매 → 입찰 통화 = 쌀
            AuctionType.UNIQUE_ITEM -> Int.MAX_VALUE // 유니크는 여기서 검증 안 함 (유산 포인트 별도 검증)
        }
        if (auctionType != AuctionType.UNIQUE_ITEM && morePoint > requiredResource) {
            val resourceName = if (auctionType == AuctionType.BUY_RICE) "금" else "쌀"
            return BidValidationResult.Fail(
                "${resourceName}이 부족합니다. (보유: $requiredResource, 필요: $morePoint, 입찰가: $bidAmount, 이전입찰: ${previousBidAmount ?: 0})"
            )
        }

        return BidValidationResult.Ok
    }

    /**
     * 유니크 아이템 경매의 전체 검증.
     *
     * PHP UniqueAuctionBidder::validateUniqueBid() 포팅.
     * 유니크 아이템은 validateBid가 아닌 이 함수만 호출하면 된다.
     *
     * 검증 규칙:
     * 1. 입찰가 >= 현재 최고가 * 1.01 (1% 이상 높게)
     * 2. 입찰가 >= 현재 최고가 + 10 (최소 10포인트 이상)
     * 3. 유산 포인트 보유량 >= morePoint (차액만 차감)
     *
     * @param bidAmount 제출 입찰가
     * @param highestBidAmount 현재 최고 입찰가 (null = 입찰 없음)
     * @param generalInheritancePoint 장수의 유산 포인트 보유량
     * @param previousBidAmount 이전 내 입찰가 (null = 첫 입찰). 차액 입찰 시 유산 포인트 검증에 사용.
     * @return [BidValidationResult.Ok] 또는 구체적인 실패 사유
     */
    fun validateUniqueBid(
        bidAmount: Int,
        highestBidAmount: Int?,
        generalInheritancePoint: Int,
        previousBidAmount: Int? = null,
    ): BidValidationResult {
        // 1. 최고가 대비 상승폭 검증
        if (highestBidAmount != null) {
            val minByPercent = (highestBidAmount * 1.01).toInt()
            val minByAbsolute = highestBidAmount + 10
            val minRequired = maxOf(minByPercent, minByAbsolute)
            if (bidAmount < minRequired) {
                return BidValidationResult.Fail(
                    "입찰가가 현재 최고가(${highestBidAmount})의 최소 상승폭($minRequired)보다 낮습니다."
                )
            }
        }

        // 2. 유산 포인트 보유량 검증 — 차액 입찰 시 morePoint만큼만 필요
        val morePoint = calculateMorePoint(bidAmount, previousBidAmount)
        if (morePoint > generalInheritancePoint) {
            return BidValidationResult.Fail(
                "유산 포인트가 부족합니다. (보유: $generalInheritancePoint, 필요: $morePoint, 입찰가: $bidAmount, 이전입찰: ${previousBidAmount ?: 0})"
            )
        }

        return BidValidationResult.Ok
    }

    /**
     * 차액 입찰 계산 — 이전 입찰이 있을 경우 추가로 필요한 금액.
     *
     * @param newBidAmount 새 입찰가
     * @param previousBidAmount 이전 입찰가 (null = 첫 입찰)
     * @return 새로 차감해야 할 금액 (morePoint)
     */
    fun calculateMorePoint(newBidAmount: Int, previousBidAmount: Int?): Int {
        return if (previousBidAmount != null) {
            newBidAmount - previousBidAmount
        } else {
            newBidAmount
        }
    }
}
