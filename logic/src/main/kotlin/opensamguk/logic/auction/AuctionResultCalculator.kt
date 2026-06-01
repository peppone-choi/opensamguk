package opensamguk.logic.auction

import kotlin.math.max
import kotlin.math.min

/**
 * 경매 마감 결과 — 롤백/유찰 처리 시 반환.
 */
data class RollbackResult(
    /** 주최자에게 반환할 자원 타입 (gold/rice/null) */
    val returnResourceType: String?,
    /** 주최자에게 반환할 자원량 */
    val returnAmount: Int,
    /** 최종 경매 상태 */
    val finalStatus: AuctionStatus,
    /** 로그 메시지 */
    val logMessage: String,
)

/**
 * 경매 거래 성사 결과 — 낙찰 처리 시 반환.
 */
data class FinishResult(
    /** 주최자가 획득할 자원 타입 (입찰자가 낸 금/쌀) */
    val hostReceiveResource: String?,
    /** 주최자가 획득할 자원량 */
    val hostReceiveAmount: Int,
    /** 낙찰자가 획득할 자원 타입 (경매 물품) */
    val bidderReceiveResource: String?,
    /** 낙찰자가 획득할 자원량 */
    val bidderReceiveAmount: Int,
    /** 유니크 아이템 지급 시 아이템 키 */
    val uniqueItemKey: String? = null,
    /** 최종 경매 상태 */
    val finalStatus: AuctionStatus,
    /** 로그 메시지 */
    val logMessage: String,
    /** 낙찰자의 유산 포인트 차감량 (UNIQUE_ITEM인 경우) */
    val bidderInheritancePointDelta: Int = 0,
)

/**
 * 경매 마감 계산기 — 롤백(유찰)과 거래 성사 시의 자원 교환 로직.
 *
 * PHP `Auction::rollback()` / `Auction::finish()`의 Kotlin 포팅.
 * 순수 함수로, 외부 상태를 변경하지 않고 결과만 계산한다.
 */
object AuctionResultCalculator {

    /** 턴당 연장 분수 계수 (1/6분) */
    private const val COEFF_EXTENSION_MINUTES_PER_BID = 1.0 / 6.0
    /** 최소 연장 시간 (1분) */
    private const val MIN_EXTENSION_MINUTES_PER_BID = 1.0
    /** 1분을 밀리초로 */
    private const val MS_PER_MINUTE = 60_000L

    /**
     * 유찰(rollback) 처리 결과를 계산한다.
     *
     * @param auctionType 경매 타입
     * @param detail 경매 상세 정보
     * @param hostGeneralId 주최자 장수 ID (0 = 시스템/NPC)
     * @return [RollbackResult] — 자원 반환 정보 포함
     */
    fun calculateRollback(
        auctionType: AuctionType,
        detail: AuctionDetail,
        hostGeneralId: Int,
    ): RollbackResult {
        val amount = detail.amount ?: 0

        return when (auctionType) {
            AuctionType.BUY_RICE -> {
                // BUY_RICE 유찰: 주최자가 쌀을 반환받음 (주최자가 팔려던 쌀)
                if (hostGeneralId > 0) {
                    RollbackResult(
                        returnResourceType = "rice",
                        returnAmount = amount,
                        finalStatus = AuctionStatus.CANCELED,
                        logMessage = "경매가 유찰되어 쌀 $amount을 회수했습니다.",
                    )
                } else {
                    RollbackResult(
                        returnResourceType = null,
                        returnAmount = 0,
                        finalStatus = AuctionStatus.CANCELED,
                        logMessage = "경매가 유찰되었습니다.",
                    )
                }
            }

            AuctionType.SELL_RICE -> {
                // SELL_RICE 유찰: 주최자가 금을 반환받음 (주최자가 팔려던 금)
                if (hostGeneralId > 0) {
                    RollbackResult(
                        returnResourceType = "gold",
                        returnAmount = amount,
                        finalStatus = AuctionStatus.CANCELED,
                        logMessage = "경매가 유찰되어 금 $amount을 회수했습니다.",
                    )
                } else {
                    RollbackResult(
                        returnResourceType = null,
                        returnAmount = 0,
                        finalStatus = AuctionStatus.CANCELED,
                        logMessage = "경매가 유찰되었습니다.",
                    )
                }
            }

            AuctionType.UNIQUE_ITEM -> {
                // UNIQUE_ITEM 유찰: 단순 CANCELED 처리
                RollbackResult(
                    returnResourceType = null,
                    returnAmount = 0,
                    finalStatus = AuctionStatus.CANCELED,
                    logMessage = "유니크 아이템 경매가 유찰되었습니다.",
                )
            }
        }
    }

    /**
     * 거래 성사(finish) 처리 결과를 계산한다.
     *
     * @param auctionType 경매 타입
     * @param highestBidAmount 최고 입찰가
     * @param detail 경매 상세 정보
     * @return [FinishResult] — 자원 교환 정보 포함
     */
    fun calculateFinish(
        auctionType: AuctionType,
        highestBidAmount: Int,
        detail: AuctionDetail,
    ): FinishResult {
        val tradeAmount = detail.amount ?: 0

        return when (auctionType) {
            AuctionType.BUY_RICE -> {
                // BUY_RICE: 입찰자 금 → 주최자, 주최자 쌀 → 입찰자
                FinishResult(
                    hostReceiveResource = "gold",
                    hostReceiveAmount = highestBidAmount,
                    bidderReceiveResource = "rice",
                    bidderReceiveAmount = tradeAmount,
                    finalStatus = AuctionStatus.FINISHED,
                    logMessage = "쌀 구매 경매가 완료되었습니다. (금 $highestBidAmount → 쌀 $tradeAmount)",
                )
            }

            AuctionType.SELL_RICE -> {
                // SELL_RICE: 입찰자 쌀 → 주최자, 주최자 금 → 입찰자
                FinishResult(
                    hostReceiveResource = "rice",
                    hostReceiveAmount = highestBidAmount,
                    bidderReceiveResource = "gold",
                    bidderReceiveAmount = tradeAmount,
                    finalStatus = AuctionStatus.FINISHED,
                    logMessage = "금 구매 경매가 완료되었습니다. (쌀 $highestBidAmount → 금 $tradeAmount)",
                )
            }

            AuctionType.UNIQUE_ITEM -> {
                // UNIQUE_ITEM: 유산 포인트 차감 + 아이템 지급
                FinishResult(
                    hostReceiveResource = null,
                    hostReceiveAmount = 0,
                    bidderReceiveResource = null,
                    bidderReceiveAmount = 0,
                    finalStatus = AuctionStatus.FINISHED,
                    logMessage = "유니크 아이템 경매가 완료되었습니다. (유산 포인트 $highestBidAmount 사용)",
                    bidderInheritancePointDelta = -highestBidAmount,
                )
            }
        }
    }

    /**
     * 입찰 발생 시 마감 시간 연장을 계산한다.
     *
     * @param now 현재 시간 (epoch millisecond)
     * @param closeAt 현재 마감 시간 (epoch millisecond)
     * @param turnMinutes 턴 간격 (분)
     * @param availableLatestBidCloseDate 최대 연장 한계 (epoch millisecond, null = 무제한)
     * @return 새로운 마감 시간 (epoch millisecond)
     */
    fun extendCloseDate(
        now: Long,
        closeAt: Long,
        turnMinutes: Int,
        availableLatestBidCloseDate: Long? = null,
    ): Long {
        val extendMinutes = max(
            MIN_EXTENSION_MINUTES_PER_BID,
            turnMinutes * COEFF_EXTENSION_MINUTES_PER_BID,
        )
        val extendedMs = now + (extendMinutes * MS_PER_MINUTE).toLong()

        // 현재 마감 시간보다 이전이면 연장 안 함
        if (extendedMs <= closeAt) return closeAt

        // 최대 연장 한계 적용
        if (availableLatestBidCloseDate != null && extendedMs > availableLatestBidCloseDate) {
            return availableLatestBidCloseDate
        }

        return extendedMs
    }

    /**
     * 최고 입찰자 조회 시 사용할 정렬 기준을 결정한다.
     *
     * @param isReverse 역경매 여부
     * @return SQL ORDER BY 절에 사용할 문자열 (로직상 비교용 힌트)
     */
    fun bidOrderDirection(isReverse: Boolean): String =
        if (isReverse) "ASC" else "DESC"

    /**
     * 두 입찰가 중 우승 입찰가를 선택한다.
     *
     * @param amount1 첫 번째 입찰가
     * @param amount2 두 번째 입찰가
     * @param isReverse 역경매 여부
     * @return 우승 입찰가
     */
    fun selectWinningBid(amount1: Int, amount2: Int, isReverse: Boolean): Int {
        return if (isReverse) {
            min(amount1, amount2) // 역경매: 낮은 가격 우승
        } else {
            max(amount1, amount2) // 일반: 높은 가격 우승
        }
    }
}
