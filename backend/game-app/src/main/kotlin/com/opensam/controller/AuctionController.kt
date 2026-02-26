package com.opensam.controller

import com.opensam.dto.BidRequest
import com.opensam.dto.BidResourceAuctionRequest
import com.opensam.dto.CancelAuctionRequest
import com.opensam.dto.CreateAuctionRequest
import com.opensam.dto.CreateItemAuctionRequest
import com.opensam.dto.MarketTradeRequest
import com.opensam.dto.MessageResponse
import com.opensam.dto.OpenResourceAuctionRequest
import com.opensam.service.AuctionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class AuctionController(
    private val auctionService: AuctionService,
) {
    @GetMapping("/worlds/{worldId}/auctions")
    fun listAuctions(@PathVariable worldId: Long): ResponseEntity<List<MessageResponse>> {
        return ResponseEntity.ok(auctionService.listAuctions(worldId).map { MessageResponse.from(it) })
    }

    @PostMapping("/worlds/{worldId}/auctions")
    fun createAuction(
        @PathVariable worldId: Long,
        @RequestBody request: CreateAuctionRequest,
    ): ResponseEntity<MessageResponse> {
        return try {
            val auction = auctionService.createAuction(
                worldId,
                request.type,
                request.sellerId,
                request.item,
                request.amount,
                request.minPrice,
                request.finishBidAmount,
                request.closeTurnCnt,
            )
            ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(auction))
        } catch (e: IllegalArgumentException) {
            throw org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, e.message ?: "잘못된 요청")
        }
    }

    @GetMapping("/worlds/{worldId}/auctions/resource")
    fun getActiveResourceAuctionList(@PathVariable worldId: Long): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(auctionService.getActiveResourceAuctionList(worldId))
    }

    @PostMapping("/worlds/{worldId}/auctions/resource/buy-rice")
    fun openBuyRiceAuction(
        @PathVariable worldId: Long,
        @RequestBody request: OpenResourceAuctionRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = auctionService.openBuyRiceAuction(
            worldId = worldId,
            hostGeneralId = request.hostGeneralId,
            amount = request.amount,
            closeTurnCnt = request.closeTurnCnt,
            startBidAmount = request.startBidAmount,
            finishBidAmount = request.finishBidAmount,
        )
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PostMapping("/worlds/{worldId}/auctions/resource/sell-rice")
    fun openSellRiceAuction(
        @PathVariable worldId: Long,
        @RequestBody request: OpenResourceAuctionRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = auctionService.openSellRiceAuction(
            worldId = worldId,
            hostGeneralId = request.hostGeneralId,
            amount = request.amount,
            closeTurnCnt = request.closeTurnCnt,
            startBidAmount = request.startBidAmount,
            finishBidAmount = request.finishBidAmount,
        )
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.status(HttpStatus.CREATED).body(result)
    }

    @PostMapping("/auctions/{id}/bid")
    fun bid(
        @PathVariable id: Long,
        @RequestBody request: BidRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = auctionService.bid(id, request.bidderId, request.amount)
            ?: return ResponseEntity.notFound().build()
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/auctions/{id}/bid-resource")
    fun bidResourceAuction(
        @PathVariable id: Long,
        @RequestBody request: BidResourceAuctionRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = auctionService.bidResourceAuction(id, request.bidderId, request.amount)
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/worlds/{worldId}/market/buy-rice")
    fun buyRice(
        @PathVariable worldId: Long,
        @RequestBody request: MarketTradeRequest,
    ): ResponseEntity<Map<String, Any>> {
        val general = auctionService.buyRice(request.generalId, request.amount)
        if (general.containsKey("error")) return ResponseEntity.badRequest().body(general)
        return ResponseEntity.ok(general)
    }

    @PostMapping("/worlds/{worldId}/market/sell-rice")
    fun sellRice(
        @PathVariable worldId: Long,
        @RequestBody request: MarketTradeRequest,
    ): ResponseEntity<Map<String, Any>> {
        val general = auctionService.sellRice(request.generalId, request.amount)
        if (general.containsKey("error")) return ResponseEntity.badRequest().body(general)
        return ResponseEntity.ok(general)
    }

    @GetMapping("/worlds/{worldId}/market-price")
    fun getMarketPrice(@PathVariable worldId: Long): ResponseEntity<AuctionService.MarketPrice> {
        return ResponseEntity.ok(auctionService.getMarketPrice(worldId))
    }

    @PostMapping("/worlds/{worldId}/item-auctions")
    fun createItemAuction(
        @PathVariable worldId: Long,
        @RequestBody request: CreateItemAuctionRequest,
    ): ResponseEntity<Map<String, Any>> {
        return try {
            val auction = auctionService.createAuction(request.generalId, request.itemType, request.startPrice)
            ResponseEntity.status(HttpStatus.CREATED).body(
                mapOf("id" to auction.id, "status" to auction.status, "expiresAt" to auction.expiresAt.toString())
            )
        } catch (e: IllegalArgumentException) {
            ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "잘못된 요청")))
        }
    }

    @PostMapping("/auctions/{id}/cancel")
    fun cancelAuction(
        @PathVariable id: Long,
        @RequestBody request: CancelAuctionRequest,
    ): ResponseEntity<Map<String, Any>> {
        val result = auctionService.cancelAuction(request.generalId, id)
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/auctions/{id}/finalize")
    fun finalizeAuction(@PathVariable id: Long): ResponseEntity<Map<String, Any>> {
        val result = auctionService.finalizeAuction(id)
        if (result.containsKey("error")) return ResponseEntity.badRequest().body(result)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/worlds/{worldId}/auction-history")
    fun getAuctionHistory(@PathVariable worldId: Long): ResponseEntity<List<Map<String, Any?>>> {
        val history = auctionService.getAuctionHistory(worldId).map {
            mapOf<String, Any?>(
                "id" to it.id,
                "sellerGeneralId" to it.sellerGeneralId,
                "buyerGeneralId" to it.buyerGeneralId,
                "itemCode" to it.itemCode,
                "minPrice" to it.minPrice,
                "currentPrice" to it.currentPrice,
                "status" to it.status,
                "createdAt" to it.createdAt.toString(),
                "expiresAt" to it.expiresAt.toString(),
            )
        }
        return ResponseEntity.ok(history)
    }
}
