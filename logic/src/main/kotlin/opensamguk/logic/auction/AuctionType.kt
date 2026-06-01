package opensamguk.logic.auction

/**
 * 경매 타입 — PHP `AuctionType` enum의 Kotlin 포팅.
 *
 * | 타입        | 입찰 통화      | 주최자       | 거래 흐름                                    |
 * |------------|---------------|-------------|---------------------------------------------|
 * | BUY_RICE   | 금(gold)      | NPC/장수     | 입찰자 금 → 주최자, 주최자 쌀 → 입찰자         |
 * | SELL_RICE  | 쌀(rice)      | NPC/장수     | 입찰자 쌀 → 주최자, 주최자 금 → 입찰자         |
 * | UNIQUE_ITEM| 유산 포인트    | 시스템       | 최고 입찰자에게 아이템 지급                    |
 */
enum class AuctionType {
    BUY_RICE,
    SELL_RICE,
    UNIQUE_ITEM,
}

/**
 * 경매 상태 — PHP `AuctionStatus` enum의 Kotlin 포팅.
 */
enum class AuctionStatus {
    /** 입찰 가능 */
    OPEN,
    /** 마감 대기 중 (worker에 의해 설정) */
    FINALIZING,
    /** 거래 완료 */
    FINISHED,
    /** 취소/유찰 */
    CANCELED,
}
