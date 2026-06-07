package opensamguk.gameapi.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 경매 read API 응답 DTO (D1-D3, PHP grand truth에 1:1 정렬).
 *
 * PHP grand truth:
 *  - D1 `API/Auction/GetActiveResourceAuctionList.php` — 자원 경매(buyRice/sellRice)
 *  - D2 `API/Auction/GetUniqueItemAuctionList.php`     — 유니크 아이템 목록
 *  - D3 `API/Auction/GetUniqueItemAuctionDetail.php`   — 유니크 아이템 상세
 */

/**
 * D1: 자원 경매 per-item 와이어.
 *
 * PHP `$rawAuction`(GetActiveResourceAuctionList.php:85-95)와 정확히 동일한 필드셋:
 * `{id, type, hostGeneralID, hostName, openDate, closeDate, amount, startBidAmount, finishBidAmount, highestBid}`.
 *
 * - [type]      = `$auction->type->value` (소문자: buyRice/sellRice)
 * - [hostName]  = `$auction->detail->hostName` 만 사용(live general JOIN 폴백 없음).
 * - [openDate]/[closeDate] = `TimeUtil::format($date, false)` → 'yyyy-MM-dd HH:mm:ss' 문자열.
 * - PHP 키는 `hostGeneralID` upper-ID지만 본 프로젝트 와이어 컨벤션(camelCase) 유지.
 */
data class AuctionResourceItem(
    val id: Int,
    val type: String,
    val hostGeneralId: Int,
    /** detail.hostName (PHP `$auction->detail->hostName`). */
    val hostName: String,
    /** TimeUtil::format(openDate, false) — 'yyyy-MM-dd HH:mm:ss'. */
    val openDate: String,
    /** TimeUtil::format(closeDate, false). */
    val closeDate: String,
    val amount: Int,
    val startBidAmount: Int,
    val finishBidAmount: Int?,
    /** 최고 입찰(MAX amount). 입찰 없으면 null. */
    val highestBid: AuctionHighestBid?,
)

/**
 * D1: 활성 자원 경매 목록 envelope.
 * PHP 반환 구조 동형: `{result, buyRice, sellRice, recentLogs, generalID}`.
 */
data class AuctionResourceListResponse(
    val result: Boolean,
    val buyRice: List<AuctionResourceItem>,
    val sellRice: List<AuctionResourceItem>,
    /** BLOCKED: PHP `getAuctionLogRecent(20)`(_auctionlog.txt) 원천 미포팅 → 빈 배열. */
    val recentLogs: List<String>,
    /** BLOCKED: game-api에 세션 없음 → 0(PHP `$session->generalID`). */
    @get:JsonProperty("generalID")
    val generalID: Int,
)

/**
 * D2: 유니크 아이템 경매 목록 per-item 와이어.
 *
 * PHP `$response[]`(GetUniqueItemAuctionList.php:81-97)와 정확히 동일한 필드셋:
 * `{id, finished, title, target, isCallerHost, hostName, closeDate, remainCloseDateExtensionCnt,
 *   availableLatestBidCloseDate, highestBid}`.
 */
data class UniqueItemAuctionItem(
    val id: Int,
    val finished: Boolean,
    /** detail.title. */
    val title: String,
    val target: String?,
    /** `$auction->hostGeneralID === $generalID`. 세션 없음 → 항상 false(generalID=0). */
    @get:JsonProperty("isCallerHost")
    val isCallerHost: Boolean,
    /** detail.hostName (난독화된 host 이름). */
    val hostName: String,
    /** TimeUtil::format(closeDate, false). */
    val closeDate: String,
    val remainCloseDateExtensionCnt: Int?,
    /** TimeUtil::format(detail.availableLatestBidCloseDate, false). null 가능. */
    val availableLatestBidCloseDate: String?,
    /** 최고 입찰. PHP는 highestBid==null 경매를 skip하므로 항상 non-null. */
    val highestBid: AuctionUniqueHighestBid,
)

/**
 * D2: 유니크 아이템 경매 목록 envelope.
 * PHP 반환 구조 동형: `{result, list, obfuscatedName}`.
 */
data class UniqueItemAuctionListResponse(
    val result: Boolean,
    val list: List<UniqueItemAuctionItem>,
    /**
     * PHP `AuctionUniqueItem::genObfuscatedName($session->generalID)` — viewer 본인의 난독 이름(1회 계산).
     * BLOCKED: 세션 generalID(0) + game_env KV hiddenSeed 모두 본 read 컨트롤러에 미주입 → null(날조 금지).
     */
    val obfuscatedName: String?,
)

/**
 * D3: 유니크 아이템 경매 상세 — nested `auction` 객체.
 *
 * PHP `auction`(GetUniqueItemAuctionDetail.php:88-98)와 정확히 동일한 필드셋:
 * `{id, finished, title, target, isCallerHost, hostName, closeDate, remainCloseDateExtensionCnt,
 *   availableLatestBidCloseDate}`.
 */
data class UniqueItemAuctionDetail(
    val id: Int,
    val finished: Boolean,
    val title: String,
    val target: String?,
    @get:JsonProperty("isCallerHost")
    val isCallerHost: Boolean,
    val hostName: String,
    val closeDate: String,
    val remainCloseDateExtensionCnt: Int?,
    val availableLatestBidCloseDate: String?,
)

/**
 * D3: 유니크 아이템 경매 상세 응답 — nested envelope.
 * PHP 반환 구조 동형: `{result, auction, bidList, obfuscatedName, remainPoint}`.
 */
data class UniqueItemAuctionDetailResponse(
    val result: Boolean,
    val auction: UniqueItemAuctionDetail,
    /** amount DESC 입찰 목록. */
    val bidList: List<UniqueItemAuctionBid>,
    /**
     * PHP `AuctionUniqueItem::genObfuscatedName($session->generalID)`(caller 난독, 1회).
     * BLOCKED: 세션 generalID + game_env KV hiddenSeed 미주입 → null.
     */
    val obfuscatedName: String?,
    /**
     * PHP `InheritancePointManager::getInheritancePoint(general, previous)`.
     * BLOCKED: viewer-specific(유산 포인트 previous KV) + 세션 없음 → null.
     */
    val remainPoint: Int?,
)

/**
 * D1 최고 입찰 와이어 — PHP `highestBid` 블록(자원 경매).
 * PHP: `{amount, date, generalID, generalName}`(GetActiveResourceAuctionList.php:101-106).
 *
 * - [date]        = `TimeUtil::format($bid->date, false)` 문자열.
 * - [generalName] = `ng_auction_bid.aux.generalName`(입찰 시각 스냅샷).
 */
data class AuctionHighestBid(
    val amount: Int,
    /** TimeUtil::format(date, false) — 'yyyy-MM-dd HH:mm:ss'. */
    val date: String,
    @get:JsonProperty("generalID")
    val generalID: Int,
    /** aux.generalName(입찰 당시 스냅샷). 누락 시 null. */
    val generalName: String?,
)

/**
 * D2/D3 유니크 경매 입찰 와이어 — PHP `highestBid`/`$responseBid`(유니크).
 * PHP: `{generalName, amount, isCallerHighestBidder, date}`
 * (GetUniqueItemAuctionList.php:91-96, GetUniqueItemAuctionDetail.php:69-74).
 *
 * raw `generalId`는 노출하지 않는다(viewer 비교 결과 `isCallerHighestBidder`만).
 */
data class AuctionUniqueHighestBid(
    /** aux.generalName(입찰 당시 스냅샷). */
    val generalName: String?,
    val amount: Int,
    /** `$bid->generalID === $generalID`. 세션 없음 → 항상 false(generalID=0). */
    @get:JsonProperty("isCallerHighestBidder")
    val isCallerHighestBidder: Boolean,
    /**
     * D2(list)는 PHP raw `$highestBid->date`(DateTime) 전달, D3(detail)는 `TimeUtil::format(...,false)`.
     * 본 프로젝트는 두 경우 모두 'yyyy-MM-dd HH:mm:ss' 문자열로 통일(FE 사전식 비교 정합).
     */
    val date: String,
)

/** D3 bidList 항목 별칭 — D2 highestBid와 동형 와이어. */
typealias UniqueItemAuctionBid = AuctionUniqueHighestBid
