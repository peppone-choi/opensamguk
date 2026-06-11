package opensamguk.logic.auction

import opensamguk.common.constants.GameConst

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
 * 경매 입찰 검증기 — PHP `Auction::_bid()`(Auction.php:350-455) /
 * `Auction::bidInheritPoint()`(Auction.php:278-348)의 검증 절반(순수 계산부) 충실 포팅.
 *
 * 거부 문자열은 전부 PHP verbatim이다. PHP `_bid`에는 startBidAmount 게이트가 없고
 * (API 레이어 BidBuyRiceAuction.php:17-28도 int/required만 검사) 시작가는 프론트 표시용이다 —
 * 시작가 게이트를 두지 않는다(날조 금지).
 *
 * 검증 순서(자원 경매, Auction.php):
 *  1. 즉시판매가 캡 — `finishBidAmount < amount`(정방향) / `> amount`(역방향) 거부 (:368-376)
 *  2. 상회입찰 — `amount <= highest`(정방향) / `>= highest`(역방향) 거부 (:388-396)
 *  3. 재원 — `getVar(res) < morePoint + minReqRes` 거부 (:405-414).
 *     morePoint = amount - (유효한 내 이전 입찰액)(:405) — 이전 입찰 무효화 규칙(:399-403)은
 *     호출자가 적용한 뒤 previousBidAmount 로 전달한다.
 *     minReqRes = GameConst::$defaultGold / $defaultRice (GameConstBase.php:136,138 = 1000).
 *
 * 검증 순서(유산포인트 경매, Auction.php bidInheritPoint):
 *  1. `amount < highest * 1.01` 거부 (:286-288) — float 비교(반올림/절사 없음)
 *  2. `amount < highest + 10` 거부 (:289-291)
 *  3. `currPoint === null || currPoint < morePoint` 거부 (:300-303)
 */
object AuctionBidValidator {

    /**
     * 자원 경매(금/쌀) 입찰 검증 — PHP `_bid`의 자원 경로(Auction.php:368-414).
     *
     * @param bidAmount 제출 입찰가
     * @param currentWinningBidAmount 현재 우승 입찰가 (null = 입찰 없음). 정방향 최고가/역방향 최저가.
     * @param finishBidAmount 즉시판매가 (null = 없음) — `detail.finishBidAmount` (:368-376)
     * @param isReverse 역경매 여부
     * @param reqResource 입찰 통화 ([ResourceType.GOLD]/[ResourceType.RICE]만 허용 —
     *        PHP match (:407-410)와 동일하게 inheritPoint는 [validateUniqueBid] 전용)
     * @param generalResource 장수의 입찰 통화 보유량 (`getVar(res)`, :412)
     * @param previousBidAmount **무효화 규칙 적용 후의** 내 이전 입찰액 (null = 첫 입찰 또는 이미 환불됨).
     *        Auction.php:399-403 — 내 이전 입찰이 현재 최고가 행(`no` 동일)이 아니면 이미 환불된
     *        입찰이므로 호출자가 null 로 만들어 전달해야 한다.
     */
    fun validateBid(
        bidAmount: Int,
        currentWinningBidAmount: Int?,
        finishBidAmount: Int?,
        isReverse: Boolean,
        reqResource: ResourceType,
        generalResource: Int,
        previousBidAmount: Int? = null,
    ): BidValidationResult {
        // 1. 즉시판매가 캡 (Auction.php:368-376) — 상회입찰 검사보다 앞.
        if (!isReverse) {
            if (finishBidAmount != null && finishBidAmount < bidAmount) {
                return BidValidationResult.Fail("즉시판매가보다 높을 수 없습니다.")
            }
        } else {
            if (finishBidAmount != null && finishBidAmount > bidAmount) {
                return BidValidationResult.Fail("즉시판매가보다 낮을 수 없습니다.")
            }
        }

        // 2. 상회입찰 (Auction.php:388-396) — 정방향 strict 초과 / 역방향 strict 미만.
        if (currentWinningBidAmount != null) {
            if (!isReverse && bidAmount <= currentWinningBidAmount) {
                return BidValidationResult.Fail("현재입찰가보다 높게 입찰해야 합니다.")
            }
            if (isReverse && bidAmount >= currentWinningBidAmount) {
                return BidValidationResult.Fail("현재입찰가보다 낮게 입찰해야 합니다.")
            }
        }

        // 3. 재원 (Auction.php:405-414) — morePoint(차액) + 기본 자원 예치(minReqRes).
        val morePoint = calculateMorePoint(bidAmount, previousBidAmount)
        val (resName, minReqRes) = when (reqResource) {
            // PHP match: gold => GameConst::$defaultGold, rice => GameConst::$defaultRice (:407-410).
            ResourceType.GOLD -> "금" to GameConst.defaultGold
            ResourceType.RICE -> "쌀" to GameConst.defaultRice
            // PHP match 는 gold|rice 외 케이스가 없다(UnhandledMatchError) — 호출자 계약 위반.
            ResourceType.INHERITANCE_POINT ->
                throw IllegalArgumentException("inheritPoint 경매는 validateUniqueBid를 사용해야 합니다.")
        }
        if (generalResource < morePoint + minReqRes) {
            // PHP `$resType->getName() . '이 부족합니다.'` (:413) — '금이 부족합니다.'/'쌀이 부족합니다.'
            return BidValidationResult.Fail("${resName}이 부족합니다.")
        }

        return BidValidationResult.Ok
    }

    /**
     * 유산포인트(유니크 아이템) 경매 입찰 검증 — PHP `bidInheritPoint`(Auction.php:278-303).
     *
     * @param bidAmount 제출 입찰가
     * @param highestBidAmount 현재 최고 입찰가 (null = 입찰 없음)
     * @param currentPoint 장수 소유주의 `previous[0]` 유산포인트 (null = 저장소 부재 —
     *        PHP `getInheritancePoint` null 케이스(:300-301)와 동일하게 즉시 거부)
     * @param previousBidAmount **무효화 규칙 적용 후의** 내 이전 입찰액 (Auction.php:293-297) —
     *        [validateBid]와 동일하게 호출자가 무효화를 적용해 전달한다.
     */
    fun validateUniqueBid(
        bidAmount: Int,
        highestBidAmount: Int?,
        currentPoint: Double?,
        previousBidAmount: Int? = null,
    ): BidValidationResult {
        // 1. 1% 상승폭 (Auction.php:286-288) — float 비교, toInt 절사 없음.
        if (highestBidAmount != null && bidAmount < highestBidAmount * 1.01) {
            return BidValidationResult.Fail("현재입찰가보다 1% 높게 입찰해야 합니다.")
        }
        // 2. +10 포인트 상승폭 (Auction.php:289-291).
        if (highestBidAmount != null && bidAmount < highestBidAmount + 10) {
            return BidValidationResult.Fail("현재입찰가보다 10 포인트 높게 입찰해야 합니다.")
        }

        // 3. 유산포인트 보유량 (Auction.php:299-303) — `currPoint === null || currPoint < morePoint`.
        val morePoint = calculateMorePoint(bidAmount, previousBidAmount)
        if (currentPoint == null || currentPoint < morePoint) {
            return BidValidationResult.Fail("유산포인트가 부족합니다.")
        }

        return BidValidationResult.Ok
    }

    /**
     * 차액 입찰 계산 — PHP `$morePoint = $amount - ($myPrevBid ? $myPrevBid->amount : 0)`
     * (Auction.php:405 / :299). previousBidAmount 는 무효화 규칙 적용 후 값이어야 한다.
     */
    fun calculateMorePoint(newBidAmount: Int, previousBidAmount: Int?): Int {
        return if (previousBidAmount != null) {
            newBidAmount - previousBidAmount
        } else {
            newBidAmount
        }
    }
}
