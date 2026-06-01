package opensamguk.logic.auction

import kotlinx.serialization.Serializable

/**
 * 경매 상세 정보 — DB `auction.detail` JSONB 컬럼의 Kotlin 직렬화 DTO.
 *
 * PHP `Auction::$detail` 필드의 Kotlin 포팅. 모든 필드는 nullable 또는 기본값을 가지며,
 * 역직렬화 시 누락된 필드는 안전하게 기본값으로 채워진다.
 *
 * @property title 경매 제목 (표시용)
 * @property amount 거래량 (자원 경매: 쌀/금의 양)
 * @property isReverse 역경매 여부 — true면 최저 입찰가 우승
 * @property startBidAmount 시작 입찰가 (최소 입찰 하한)
 * @property finishBidAmount 마감 입찰가 (기록용)
 * @property availableLatestBidCloseDate 최대 마감 연장 한계 (ISO-8601 문자열)
 * @property remainCloseDateExtensionCnt 남은 마감 연장 횟수
 */
@Serializable
data class AuctionDetail(
    val title: String? = null,
    val amount: Int? = null,
    val isReverse: Boolean = false,
    val startBidAmount: Int? = null,
    val finishBidAmount: Int? = null,
    val availableLatestBidCloseDate: String? = null,
    val remainCloseDateExtensionCnt: Int? = null,
)
