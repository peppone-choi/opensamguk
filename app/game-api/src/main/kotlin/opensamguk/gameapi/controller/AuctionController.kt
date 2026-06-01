package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.AuctionBidResponse
import opensamguk.gameapi.dto.AuctionResponse
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auctions")
class AuctionController(
    private val auctionRepository: AuctionRepository,
    private val auctionBidRepository: AuctionBidRepository,
) {

    @GetMapping
    fun listActive(): ResponseEntity<List<AuctionResponse>> {
        val auctions = auctionRepository.findByFinishedFalse()
            .map { it.toResponse() }
        return ResponseEntity.ok(auctions)
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: Int): ResponseEntity<AuctionResponse> {
        val auction = auctionRepository.findById(id)
            .orElse(null) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(auction.toResponse())
    }

    @GetMapping("/{id}/bids")
    fun bids(@PathVariable id: Int): ResponseEntity<List<AuctionBidResponse>> {
        val bids = auctionBidRepository.findByAuctionIdOrderByAmountDesc(id)
            .map { it.toResponse() }
        return ResponseEntity.ok(bids)
    }

    private fun opensamguk.infra.entity.AuctionEntity.toResponse() = AuctionResponse(
        id = id ?: 0,
        type = type.name,
        finished = finished,
        target = target,
        hostGeneralId = hostGeneralId,
        reqResource = reqResource.name,
        openDate = openDate,
        closeDate = closeDate,
        detail = detail,
    )

    private fun opensamguk.infra.entity.AuctionBidEntity.toResponse() = AuctionBidResponse(
        no = no,
        auctionId = auctionId,
        generalId = generalId,
        owner = owner,
        amount = amount,
        date = date,
        aux = aux,
    )
}
