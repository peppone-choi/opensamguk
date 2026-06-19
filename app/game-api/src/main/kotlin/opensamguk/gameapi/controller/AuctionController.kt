package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AuctionHighestBid
import opensamguk.gameapi.dto.AuctionResourceItem
import opensamguk.gameapi.dto.AuctionResourceListResponse
import opensamguk.gameapi.dto.AuctionUniqueHighestBid
import opensamguk.gameapi.dto.UniqueItemAuctionDetail
import opensamguk.gameapi.dto.UniqueItemAuctionDetailResponse
import opensamguk.gameapi.dto.UniqueItemAuctionItem
import opensamguk.gameapi.dto.UniqueItemAuctionListResponse
import opensamguk.infra.entity.AuctionBidEntity
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.util.jsonDecode
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 경매 read API (D1-D3, PHP grand truth에 1:1 정렬).
 *
 * PHP grand truth:
 *  - D1 `API/Auction/GetActiveResourceAuctionList.php` — 자원 경매(buyRice/sellRice) envelope
 *  - D2 `API/Auction/GetUniqueItemAuctionList.php`     — 유니크 아이템 목록
 *  - D3 `API/Auction/GetUniqueItemAuctionDetail.php`   — 유니크 아이템 상세
 *
 * 인증 divergence(EXECUTION_PLAN §read 규칙): game-api에 세션 필터 없음 → viewer generalID = 0.
 * 따라서 `generalID`/`isCallerHost`/`isCallerHighestBidder`는 0 대조(graceful, parityViolation 아님).
 *
 * BLOCKED(§W3_PLAN §2, 값 날조 금지):
 *  - `recentLogs`(PHP `getAuctionLogRecent(20)`, _auctionlog.txt) — 원천 미포팅 → 빈 배열.
 *  - `obfuscatedName`(PHP `genObfuscatedName($generalID)`) — 세션 generalID + game_env KV hiddenSeed
 *    모두 본 컨트롤러에 미주입 → null.
 *  - `remainPoint`(PHP InheritancePointManager previous) — viewer-specific + 세션 없음 → null.
 *
 * read-only — 절대 write 없음.
 */
@RestController
@RequestMapping("/api/auctions")
class AuctionController(
    private val auctionRepository: AuctionRepository,
    private val auctionBidRepository: AuctionBidRepository,
) {

    /** 세션 미연동 — viewer generalID 항상 0. */
    private val viewerGeneralId = 0

    /** PHP `TimeUtil::format($date, false)` 동치: 'yyyy-MM-dd HH:mm:ss'(Asia/Seoul). */
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.of("Asia/Seoul"))

    // ── D1: 활성 자원 경매 목록 ───────────────────────────────────────────────

    /**
     * PHP: `SELECT * FROM ng_auction WHERE type IN (buyRice,sellRice) AND finished=0 ORDER BY close_date ASC`.
     * 두 타입을 합쳐 close_date ASC 정렬 후 type별로 분배.
     */
    @GetMapping
    fun listActive(): ResponseEntity<AuctionResourceListResponse> {
        val auctions = (
            auctionRepository.findByFinishedFalseAndTypeValue(AuctionType.BUY_RICE.value) +
                auctionRepository.findByFinishedFalseAndTypeValue(AuctionType.SELL_RICE.value)
            ).sortedBy { it.closeDate } // PHP ORDER BY close_date ASC

        val highestBids = resolveHighestBids(auctions)

        val buyRice = ArrayList<AuctionResourceItem>()
        val sellRice = ArrayList<AuctionResourceItem>()
        for (auction in auctions) {
            val item = auction.toResourceItem(highestBids[auction.id ?: 0])
            // PHP: type == BuyRice->value → buyRiceList, else sellRiceList
            if (auction.type == AuctionType.BUY_RICE) buyRice.add(item) else sellRice.add(item)
        }

        return ResponseEntity.ok(
            AuctionResourceListResponse(
                result = true,
                buyRice = buyRice,
                sellRice = sellRice,
                recentLogs = emptyList(), // BLOCKED: 원천 테이블 미확정
                generalID = viewerGeneralId, // BLOCKED: 세션 컨텍스트 없음
            )
        )
    }

    // ── D2: 유니크 아이템 경매 목록 ───────────────────────────────────────────

    /**
     * PHP: `SELECT * FROM ng_auction WHERE type = uniqueItem ORDER BY close_date ASC`(finished 무필터).
     * highestBid==null 경매는 skip(PHP continue).
     */
    @GetMapping("/unique")
    fun listUniqueItems(): ResponseEntity<UniqueItemAuctionListResponse> {
        val auctions = auctionRepository.findByTypeValueOrderByCloseDateAsc(AuctionType.UNIQUE_ITEM.value)
        val highestBids = resolveHighestBids(auctions)

        val list = auctions.mapNotNull { auction ->
            val top = highestBids[auction.id ?: 0] ?: return@mapNotNull null // highestBid null → skip
            val detail = decodeDetail(auction.detail)
            UniqueItemAuctionItem(
                id = auction.id ?: 0,
                finished = auction.finished,
                title = detail.title,
                target = auction.target,
                isCallerHost = auction.hostGeneralId == viewerGeneralId,
                hostName = detail.hostName,
                closeDate = formatInstant(auction.closeDate),
                remainCloseDateExtensionCnt = detail.remainCloseDateExtensionCnt,
                availableLatestBidCloseDate = formatIsoString(detail.availableLatestBidCloseDate),
                highestBid = top.toUniqueBid(),
            )
        }

        return ResponseEntity.ok(
            UniqueItemAuctionListResponse(
                result = true,
                list = list,
                obfuscatedName = null, // BLOCKED: 세션 generalID + game_env KV hiddenSeed 미주입
            )
        )
    }

    // ── D3: 유니크 아이템 경매 상세 ───────────────────────────────────────────

    /**
     * PHP: `SELECT * FROM ng_auction WHERE type = uniqueItem AND id = %i`.
     * 부재 시 PHP는 한글 문자열 '선택한 경매가 없습니다.'를 반환(launch가 string 반환 = 에러).
     */
    @GetMapping("/{id}/unique-detail")
    fun uniqueDetail(@PathVariable id: Int): ResponseEntity<Any> {
        val auction = auctionRepository.findById(id).orElse(null)
        if (auction == null || auction.type != AuctionType.UNIQUE_ITEM) {
            // PHP: return '선택한 경매가 없습니다.'(GetUniqueItemAuctionDetail.php:55-57)
            // text/plain;charset=UTF-8 명시 — 한글 바이트가 ISO-8859-1로 디코드돼 깨지지 않도록.
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType(MediaType.TEXT_PLAIN, Charsets.UTF_8))
                .body("선택한 경매가 없습니다.")
        }

        val detail = decodeDetail(auction.detail)
        // PHP: bidList ORDER BY amount DESC
        val bids = auctionBidRepository.findByAuctionIdOrderByAmountDesc(id)

        return ResponseEntity.ok(
            UniqueItemAuctionDetailResponse(
                result = true,
                auction = UniqueItemAuctionDetail(
                    id = auction.id ?: 0,
                    finished = auction.finished,
                    title = detail.title,
                    target = auction.target,
                    isCallerHost = auction.hostGeneralId == viewerGeneralId,
                    hostName = detail.hostName,
                    closeDate = formatInstant(auction.closeDate),
                    remainCloseDateExtensionCnt = detail.remainCloseDateExtensionCnt,
                    availableLatestBidCloseDate = formatIsoString(detail.availableLatestBidCloseDate),
                ),
                bidList = bids.map { it.toUniqueBid() },
                obfuscatedName = null, // BLOCKED: 세션 generalID + game_env KV hiddenSeed 미주입
                remainPoint = null,    // BLOCKED: viewer-specific(유산 포인트), 세션 없음
            )
        )
    }

    // ── 포맷 / 디코드 헬퍼 ───────────────────────────────────────────────────

    /** PHP `TimeUtil::format($instant, false)`. */
    private fun formatInstant(instant: Instant): String = dateFormatter.format(instant)

    /**
     * detail jsonb의 ISO 문자열(quarantined wall-clock)을 'yyyy-MM-dd HH:mm:ss'로 정규화.
     * 파싱 불가하면 원본 그대로(null이면 null). PHP는 DateTime이라 항상 포맷되지만 본 프로젝트는
     * 문자열로 carry하므로 best-effort 파싱.
     */
    private fun formatIsoString(iso: String?): String? {
        if (iso == null) return null
        return runCatching { dateFormatter.format(Instant.parse(iso)) }.getOrDefault(iso)
    }

    private fun decodeDetail(detailJson: String): AuctionInfoDetail =
        runCatching { AuctionInfoDetail.fromArray(jsonDecode(detailJson)) }.getOrDefault(
            AuctionInfoDetail(title = "", hostName = "", amount = 0, startBidAmount = 0)
        )

    /** 경매 목록의 per-auction 최고 입찰 맵(auctionId → AuctionBidEntity). N+1 회피. */
    private fun resolveHighestBids(auctions: List<AuctionEntity>): Map<Int, AuctionBidEntity> {
        val auctionIds = auctions.mapNotNull { it.id }
        if (auctionIds.isEmpty()) return emptyMap()
        return auctionBidRepository.findHighestBidsByAuctionIds(auctionIds).associateBy { it.auctionId }
    }

    // ── Entity → Response 변환 ──────────────────────────────────────────────

    /** D1 per-item: PHP `$rawAuction`(GetActiveResourceAuctionList.php:85-95) 필드셋. */
    private fun AuctionEntity.toResourceItem(topBid: AuctionBidEntity?): AuctionResourceItem {
        val detail = decodeDetail(detail)
        return AuctionResourceItem(
            id = id ?: 0,
            type = type.value, // PHP $auction->type->value (소문자)
            hostGeneralId = hostGeneralId,
            hostName = detail.hostName, // PHP $auction->detail->hostName (live general JOIN 폴백 없음)
            openDate = formatInstant(openDate),
            closeDate = formatInstant(closeDate),
            amount = detail.amount,
            startBidAmount = detail.startBidAmount,
            finishBidAmount = detail.finishBidAmount,
            highestBid = topBid?.toResourceHighestBid(),
        )
    }

    /** D1 highestBid: PHP `{amount, date, generalID, generalName}`. */
    private fun AuctionBidEntity.toResourceHighestBid(): AuctionHighestBid {
        val auxMap = runCatching { jsonDecode(aux) }.getOrDefault(emptyMap())
        return AuctionHighestBid(
            amount = amount,
            date = formatInstant(date),
            generalID = generalId,
            generalName = auxMap["generalName"] as? String,
        )
    }

    /** D2/D3 입찰: PHP `{generalName, amount, isCallerHighestBidder, date}` (raw generalId 제거). */
    private fun AuctionBidEntity.toUniqueBid(): AuctionUniqueHighestBid {
        val auxMap = runCatching { jsonDecode(aux) }.getOrDefault(emptyMap())
        return AuctionUniqueHighestBid(
            generalName = auxMap["generalName"] as? String,
            amount = amount,
            isCallerHighestBidder = generalId == viewerGeneralId,
            date = formatInstant(date),
        )
    }
}
