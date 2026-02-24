package com.opensam.service

import com.opensam.entity.Auction
import com.opensam.entity.AuctionBid
import com.opensam.entity.Message
import com.opensam.repository.AuctionBidRepository
import com.opensam.repository.AuctionRepository
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import kotlin.math.ceil
import kotlin.math.roundToInt

@Service
class AuctionService(
    private val auctionRepository: AuctionRepository,
    private val auctionBidRepository: AuctionBidRepository,
    private val cityRepository: CityRepository,
    private val generalRepository: GeneralRepository,
) {
    data class MarketPrice(
        val worldId: Long,
        val goldPerRice: Double,
        val ricePerGold: Double,
        val supply: Long,
        val demand: Long,
    )

    @Transactional(readOnly = true)
    fun listAuctions(worldId: Long): List<Message> {
        return auctionRepository.findByWorldIdAndStatusOrderByCreatedAtDesc(worldId, "open").map { toMessage(it) }
    }

    @Transactional(readOnly = true)
    fun listActiveAuctions(worldId: Long): List<Auction> {
        return auctionRepository.findByWorldIdAndStatusOrderByCreatedAtDesc(worldId, "open")
    }

    @Transactional
    fun createAuction(worldId: Long, type: String, sellerId: Long, item: String, amount: Int, minPrice: Int, finishBidAmount: Int? = null, closeTurnCnt: Int? = null): Message {
        val seller = generalRepository.findById(sellerId).orElse(null)
            ?: throw IllegalArgumentException("장수를 찾을 수 없습니다")
        if (seller.worldId != worldId) throw IllegalArgumentException("잘못된 월드 장수입니다")
        val auction = createAuction(sellerId, if (item.isBlank()) type else item, minPrice)
        // Store additional resource auction fields in metadata
        if (finishBidAmount != null) auction.meta["finishBidAmount"] = finishBidAmount
        if (closeTurnCnt != null) auction.meta["closeTurnCnt"] = closeTurnCnt
        if (amount > 0) auction.meta["amount"] = amount
        auction.meta["subType"] = item
        auctionRepository.save(auction)
        return toMessage(auction)
    }

    @Transactional
    fun bid(id: Long, bidderId: Long, amount: Int): Map<String, Any>? {
        return placeBid(bidderId, id, amount)
    }

    @Transactional
    fun buyRice(generalId: Long, amount: Int): Map<String, Any> {
        if (amount <= 0) return mapOf("error" to "거래량은 1 이상이어야 합니다")
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return mapOf("error" to "장수를 찾을 수 없습니다")
        val market = getMarketPrice(general.worldId)
        val cost = ceil(amount * market.goldPerRice).toInt().coerceAtLeast(1)
        if (general.gold < cost) return mapOf("error" to "금이 부족합니다")

        general.gold -= cost
        general.rice += amount
        generalRepository.save(general)

        return mapOf(
            "success" to true,
            "amount" to amount,
            "costGold" to cost,
            "goldPerRice" to market.goldPerRice,
            "generalGold" to general.gold,
            "generalRice" to general.rice,
        )
    }

    @Transactional
    fun sellRice(generalId: Long, amount: Int): Map<String, Any> {
        if (amount <= 0) return mapOf("error" to "거래량은 1 이상이어야 합니다")
        val general = generalRepository.findById(generalId).orElse(null)
            ?: return mapOf("error" to "장수를 찾을 수 없습니다")
        if (general.rice < amount) return mapOf("error" to "쌀이 부족합니다")

        val market = getMarketPrice(general.worldId)
        val revenue = (amount * market.goldPerRice * 0.97).roundToInt().coerceAtLeast(1)

        general.rice -= amount
        general.gold += revenue
        generalRepository.save(general)

        return mapOf(
            "success" to true,
            "amount" to amount,
            "revenueGold" to revenue,
            "goldPerRice" to market.goldPerRice,
            "generalGold" to general.gold,
            "generalRice" to general.rice,
        )
    }

    @Transactional
    fun createAuction(generalId: Long, itemType: String, startPrice: Int): Auction {
        if (startPrice <= 0) throw IllegalArgumentException("시작가는 1 이상이어야 합니다")
        val seller = generalRepository.findById(generalId).orElse(null)
            ?: throw IllegalArgumentException("장수를 찾을 수 없습니다")
        if (seller.itemCode == "None" || seller.itemCode != itemType) {
            throw IllegalArgumentException("해당 아이템을 보유하고 있지 않습니다")
        }

        seller.itemCode = "None"
        generalRepository.save(seller)

        val now = OffsetDateTime.now()
        val auction = Auction(
            worldId = seller.worldId,
            sellerGeneralId = seller.id,
            itemCode = itemType,
            minPrice = startPrice,
            currentPrice = startPrice,
            status = "open",
            createdAt = now,
            expiresAt = now.plusHours(6),
        )
        return auctionRepository.save(auction)
    }

    @Transactional
    fun placeBid(generalId: Long, auctionId: Long, amount: Int): Map<String, Any> {
        val auction = auctionRepository.findById(auctionId).orElse(null)
            ?: return mapOf("error" to "경매가 없습니다")
        if (auction.status != "open") return mapOf("error" to "종료된 경매입니다")
        if (auction.expiresAt <= OffsetDateTime.now()) {
            finalizeAuction(auctionId)
            return mapOf("error" to "경매가 종료되었습니다")
        }
        if (amount <= auction.currentPrice) return mapOf("error" to "현재 입찰가보다 높아야 합니다")
        if (auction.sellerGeneralId == generalId) return mapOf("error" to "자신의 경매에는 입찰할 수 없습니다")

        val bidder = generalRepository.findById(generalId).orElse(null)
            ?: return mapOf("error" to "장수를 찾을 수 없습니다")
        if (bidder.worldId != auction.worldId) return mapOf("error" to "같은 월드에서만 입찰할 수 있습니다")
        if (bidder.gold < amount) return mapOf("error" to "금이 부족합니다")

        val prevHighest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
        bidder.gold -= amount
        generalRepository.save(bidder)

        if (prevHighest != null) {
            val prevBidder = generalRepository.findById(prevHighest.bidderGeneralId).orElse(null)
            if (prevBidder != null) {
                prevBidder.gold += prevHighest.amount
                generalRepository.save(prevBidder)
            }
        }

        auctionBidRepository.save(
            AuctionBid(
                auctionId = auction.id,
                bidderGeneralId = bidder.id,
                amount = amount,
                createdAt = OffsetDateTime.now(),
            )
        )

        auction.currentPrice = amount
        auction.buyerGeneralId = bidder.id
        auctionRepository.save(auction)

        return mapOf("success" to true, "currentBid" to amount, "auctionId" to auction.id)
    }

    @Transactional
    fun cancelAuction(generalId: Long, auctionId: Long): Map<String, Any> {
        val auction = auctionRepository.findById(auctionId).orElse(null)
            ?: return mapOf("error" to "경매가 없습니다")
        if (auction.status != "open") return mapOf("error" to "이미 종료된 경매입니다")
        if (auction.sellerGeneralId != generalId) return mapOf("error" to "본인 경매만 취소할 수 있습니다")

        val seller = generalRepository.findById(generalId).orElse(null)
            ?: return mapOf("error" to "장수를 찾을 수 없습니다")
        if (seller.itemCode == "None") {
            seller.itemCode = auction.itemCode
            generalRepository.save(seller)
        }

        val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
        if (highest != null) {
            val highestBidder = generalRepository.findById(highest.bidderGeneralId).orElse(null)
            if (highestBidder != null) {
                highestBidder.gold += highest.amount
                generalRepository.save(highestBidder)
            }
        }

        auction.status = "cancelled"
        auctionRepository.save(auction)
        return mapOf("success" to true, "auctionId" to auction.id, "status" to auction.status)
    }

    @Transactional
    fun finalizeAuction(auctionId: Long): Map<String, Any> {
        val auction = auctionRepository.findById(auctionId).orElse(null)
            ?: return mapOf("error" to "경매가 없습니다")
        if (auction.status != "open") return mapOf("success" to true, "auctionId" to auction.id, "status" to auction.status)

        val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
        val seller = generalRepository.findById(auction.sellerGeneralId).orElse(null)
        if (highest == null || seller == null) {
            if (seller != null && seller.itemCode == "None") {
                seller.itemCode = auction.itemCode
                generalRepository.save(seller)
            }
            auction.status = "expired"
            auctionRepository.save(auction)
            return mapOf("success" to true, "auctionId" to auction.id, "status" to auction.status)
        }

        val winner = generalRepository.findById(highest.bidderGeneralId).orElse(null)
        if (winner == null) {
            if (seller.itemCode == "None") {
                seller.itemCode = auction.itemCode
                generalRepository.save(seller)
            }
            auction.status = "failed"
            auctionRepository.save(auction)
            return mapOf("error" to "낙찰자를 찾을 수 없습니다")
        }

        seller.gold += highest.amount
        winner.itemCode = auction.itemCode
        generalRepository.save(seller)
        generalRepository.save(winner)

        auction.buyerGeneralId = winner.id
        auction.currentPrice = highest.amount
        auction.status = "closed"
        auctionRepository.save(auction)

        return mapOf(
            "success" to true,
            "auctionId" to auction.id,
            "status" to auction.status,
            "winnerGeneralId" to winner.id,
            "finalPrice" to highest.amount,
        )
    }

    @Transactional(readOnly = true)
    fun getAuctionHistory(worldId: Long): List<Auction> {
        return auctionRepository.findByWorldIdAndStatusNotOrderByCreatedAtDesc(worldId, "open")
    }

    @Transactional(readOnly = true)
    fun getMarketPrice(worldId: Long): MarketPrice {
        val generals = generalRepository.findByWorldId(worldId)
        val cities = cityRepository.findByWorldId(worldId)

        val totalRice = generals.sumOf { it.rice.toLong() }
        val totalGold = generals.sumOf { it.gold.toLong() }
        val citySupply = cities.sumOf { (it.pop + it.agri + it.comm).toLong() }
        val cityDemand = cities.sumOf { (it.level.toLong() * 1000L + it.trade.toLong() * 20L) }

        val supply = (totalRice + citySupply).coerceAtLeast(1)
        val demand = (totalGold + cityDemand).coerceAtLeast(1)
        val ratio = (demand.toDouble() / supply.toDouble()).coerceIn(0.5, 2.0)

        val avgTrade = if (cities.isEmpty()) 100.0 else cities.map { it.trade }.average()
        val tradeAdjust = (avgTrade / 100.0).coerceIn(0.9, 1.1)
        val goldPerRice = (ratio * tradeAdjust).coerceIn(0.5, 2.2)

        return MarketPrice(
            worldId = worldId,
            goldPerRice = String.format("%.3f", goldPerRice).toDouble(),
            ricePerGold = String.format("%.3f", 1.0 / goldPerRice).toDouble(),
            supply = supply,
            demand = demand,
        )
    }

    @Scheduled(fixedDelayString = "\${app.auction.expire-interval-ms:60000}")
    @Transactional
    fun processExpiredAuctions() {
        runCatching {
            val expired = auctionRepository.findByStatusAndExpiresAtLessThanEqual("open", OffsetDateTime.now())
            expired.forEach { finalizeAuction(it.id) }
        }
    }

    private fun toMessage(auction: Auction): Message {
        val sellerName = generalRepository.findById(auction.sellerGeneralId).orElse(null)?.name ?: "Unknown"
        val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
        return Message(
            id = auction.id,
            worldId = auction.worldId,
            mailboxCode = "auction",
            messageType = auction.itemCode,
            srcId = auction.sellerGeneralId,
            payload = mutableMapOf(
                "sellerName" to sellerName,
                "item" to auction.itemCode,
                "minPrice" to auction.minPrice,
                "currentBid" to auction.currentPrice,
                "bidderId" to (highest?.bidderGeneralId ?: 0L),
                "state" to auction.status,
                "expiresAt" to auction.expiresAt.toString(),
            ),
        )
    }
}
