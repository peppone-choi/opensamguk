package com.opensam.controller

import com.opensam.dto.BidRequest
import com.opensam.dto.CreateAuctionRequest
import com.opensam.dto.MessageResponse
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
        val auction = auctionService.createAuction(worldId, request.type, request.sellerId, request.item, request.amount, request.minPrice)
        return ResponseEntity.status(HttpStatus.CREATED).body(MessageResponse.from(auction))
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
}
