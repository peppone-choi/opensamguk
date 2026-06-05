package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AuctionBidResponse
import opensamguk.gameapi.dto.AuctionHighestBid
import opensamguk.gameapi.dto.AuctionResponse
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.infra.entity.AuctionBidEntity
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.util.jsonDecode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 경매 read API (W3 enriched). PHP grand truth: `API/Auction/GetActiveResourceAuctionList.php`.
 *
 * W3에서 per-auction 와이어를 PHP와 동형으로 확장:
 *  - `hostName` = host 장수 이름(general JOIN, [GeneralReadRepository]). N+1 회피를 위해 활성 경매의
 *    host 장수 id를 모아 `findAllById`로 한 번에 read 후 id→name 맵으로 룩업한다.
 *  - `highestBid` = `ng_auction_bid` MAX(amount)([AuctionBidRepository.findTopByAuctionIdOrderByAmountDesc]).
 *    generalName은 입찰 `aux.generalName`(스냅샷)에서 디코드.
 *  - `amount/startBidAmount/finishBidAmount` = `ng_auction.detail` jsonb 디코드([AuctionDetail] 동치).
 *
 * BLOCKED(§W3_PLAN §2, fabricate 금지):
 *  - `recentLogs`(PHP `getAuctionLogRecent(20)`) — 원천 테이블/필터 미확정 → 노출 안 함(빈 목록도 만들지
 *    않음; 후속 웨이브에서 log_entry 필터 확정 후 추가).
 *  - `remainPoint` — per-viewer(세션 유산포인트/금) 값이며 PHP도 경매 목록 API에선 반환하지 않는다(베팅
 *    상세 API의 책임). 베팅 상세([BettingController])에서 다룬다.
 *
 * read-only(§7) — 절대 write 없음. game-api에 Security 필터 없음 → 다른 `@RestController`처럼 공개.
 */
@RestController
@RequestMapping("/api/auctions")
class AuctionController(
    private val auctionRepository: AuctionRepository,
    private val auctionBidRepository: AuctionBidRepository,
    private val generalReadRepository: GeneralReadRepository,
) {

    @GetMapping
    fun listActive(): ResponseEntity<List<AuctionResponse>> {
        val auctions = auctionRepository.findByFinishedFalse()
        // host 장수 이름 일괄 read(N+1 회피): host id 집합 → id→name.
        val hostNames = resolveHostNames(auctions)
        val responses = auctions.map { it.toResponse(hostNames[it.hostGeneralId]) }
        return ResponseEntity.ok(responses)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Int): ResponseEntity<AuctionResponse> {
        val auction = auctionRepository.findById(id)
            .orElse(null) ?: return ResponseEntity.notFound().build()
        val hostName = generalReadRepository.findById(auction.hostGeneralId)
            .map { it.name }.orElse(null)
        return ResponseEntity.ok(auction.toResponse(hostName))
    }

    @GetMapping("/{id}/bids")
    fun bids(@PathVariable id: Int): ResponseEntity<List<AuctionBidResponse>> {
        val bids = auctionBidRepository.findByAuctionIdOrderByAmountDesc(id)
            .map { it.toResponse() }
        return ResponseEntity.ok(bids)
    }

    /** 활성 경매들의 host 장수 id → name 맵(부재 장수는 맵에 없음 → null로 materialize). */
    private fun resolveHostNames(auctions: List<AuctionEntity>): Map<Int, String> {
        val hostIds = auctions.map { it.hostGeneralId }.toSet()
        if (hostIds.isEmpty()) return emptyMap()
        return generalReadRepository.findAllById(hostIds).associate { it.id to it.name }
    }

    private fun AuctionEntity.toResponse(hostNameFallback: String?): AuctionResponse {
        // detail jsonb 디코드(hostName/amount/startBidAmount/finishBidAmount). 디코드 실패/누락은 null.
        val detailMap = runCatching { jsonDecode(detail) }.getOrDefault(emptyMap())
        // hostName: PHP는 detail.hostName(등록 시각 스냅샷)을 쓴다 → 그것을 우선, 없으면 live general JOIN.
        val hostName = (detailMap["hostName"] as? String)?.takeIf { it.isNotBlank() } ?: hostNameFallback
        // 최고 입찰(MAX amount) — 없으면 null.
        val top = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(id ?: 0)
        return AuctionResponse(
            id = id ?: 0,
            type = type.name,
            finished = finished,
            target = target,
            hostGeneralId = hostGeneralId,
            hostName = hostName,
            reqResource = reqResource.name,
            openDate = openDate,
            closeDate = closeDate,
            amount = (detailMap["amount"] as? Number)?.toInt(),
            startBidAmount = (detailMap["startBidAmount"] as? Number)?.toInt(),
            finishBidAmount = (detailMap["finishBidAmount"] as? Number)?.toInt(),
            highestBid = top?.toHighestBid(),
            detail = detail,
        )
    }

    /** 최고 입찰 → 와이어. generalName은 aux.generalName(입찰 스냅샷) 디코드. */
    private fun AuctionBidEntity.toHighestBid(): AuctionHighestBid {
        val auxMap = runCatching { jsonDecode(aux) }.getOrDefault(emptyMap())
        return AuctionHighestBid(
            amount = amount,
            date = date,
            generalId = generalId,
            generalName = auxMap["generalName"] as? String,
        )
    }

    private fun AuctionBidEntity.toResponse() = AuctionBidResponse(
        no = no,
        auctionId = auctionId,
        generalId = generalId,
        owner = owner,
        amount = amount,
        date = date,
        aux = aux,
    )
}
