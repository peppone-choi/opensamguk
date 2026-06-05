package opensamguk.gameapi.dto

import java.time.Instant

/**
 * 경매 목록/상세 응답 (W3 enriched).
 *
 * PHP grand truth: `API/Auction/GetActiveResourceAuctionList.php` 의 per-auction 와이어
 * (`id, type, hostGeneralID, hostName, openDate, closeDate, amount, startBidAmount, finishBidAmount,
 *  highestBid`).
 *
 * W3에서 추가된 필드(모두 row-backed):
 *  - [hostGeneralId] = `ng_auction.host_general_id`(V7:84) — 기존부터 노출.
 *  - [hostName]      = host 장수 이름(`general.name` JOIN). PHP는 `detail->hostName`을 쓰지만 우리의
 *    `AuctionDetail` 코덱엔 hostName 필드가 없어, 동치 원천인 host 장수 행을 read해 채운다(fabricate 아님).
 *    host 장수가 없으면(사망/삭제) null.
 *  - [highestBid]    = `ng_auction_bid` 중 MAX(amount) 입찰(없으면 null). PHP `highestBids` 동형.
 *  - [amount]/[startBidAmount]/[finishBidAmount] = `ng_auction.detail` jsonb([AuctionDetail]) 디코드.
 */
data class AuctionResponse(
    val id: Int,
    val type: String,
    val finished: Boolean,
    val target: String?,
    val hostGeneralId: Int,
    /** host 장수 이름(general JOIN). 장수 부재 시 null. */
    val hostName: String?,
    val reqResource: String,
    val openDate: Instant,
    val closeDate: Instant,
    /** 거래량(detail.amount) — 자원 경매의 쌀/금 양. */
    val amount: Int?,
    /** 시작 입찰가(detail.startBidAmount). */
    val startBidAmount: Int?,
    /** 마감 입찰가/즉시판매가(detail.finishBidAmount). */
    val finishBidAmount: Int?,
    /** 최고 입찰(MAX amount). 입찰 없으면 null. */
    val highestBid: AuctionHighestBid?,
    /** 원본 detail jsonb 문자열(프론트가 추가 필드를 직접 디코드할 수 있도록 보존). */
    val detail: String,
)

/**
 * 최고 입찰 와이어 — PHP `highestBid` 블록(`{amount, date, generalID, generalName}`).
 * `generalName`은 `ng_auction_bid.aux.generalName`(입찰 시각 스냅샷)에서 디코드.
 */
data class AuctionHighestBid(
    val amount: Int,
    val date: Instant,
    val generalId: Int,
    /** aux.generalName(입찰 당시 스냅샷). 누락 시 null. */
    val generalName: String?,
)

/** 경매 입찰 응답 */
data class AuctionBidResponse(
    val no: Int?,
    val auctionId: Int,
    val generalId: Int,
    val owner: Int?,
    val amount: Int,
    val date: Instant,
    val aux: String,
)
