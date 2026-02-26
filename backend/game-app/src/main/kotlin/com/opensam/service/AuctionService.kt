package com.opensam.service

import com.opensam.entity.Auction
import com.opensam.entity.AuctionBid
import com.opensam.entity.Message
import com.opensam.repository.AuctionBidRepository
import com.opensam.repository.AuctionRepository
import com.opensam.repository.CityRepository
import com.opensam.repository.GeneralRepository
import com.opensam.repository.WorldStateRepository
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
    private val worldStateRepository: WorldStateRepository,
) {
    companion object {
        private const val GENERAL_MINIMUM_GOLD = 100
        private const val GENERAL_MINIMUM_RICE = 100
        private const val RESOURCE_AUCTION_TYPE = "resource"
        private const val RESOURCE_SUB_TYPE_BUY_RICE = "buyRice"
        private const val RESOURCE_SUB_TYPE_SELL_RICE = "sellRice"
        private const val ITEM_AUCTION_TYPE = "item"
    }

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
        if (type == RESOURCE_AUCTION_TYPE) {
            val resolvedFinishBidAmount = finishBidAmount ?: 0
            val resolvedCloseTurnCnt = closeTurnCnt ?: 0
            val openResult = when (item) {
                RESOURCE_SUB_TYPE_BUY_RICE -> openBuyRiceAuction(worldId, sellerId, amount, resolvedCloseTurnCnt, minPrice, resolvedFinishBidAmount)
                RESOURCE_SUB_TYPE_SELL_RICE -> openSellRiceAuction(worldId, sellerId, amount, resolvedCloseTurnCnt, minPrice, resolvedFinishBidAmount)
                else -> mapOf("error" to "지원하지 않는 자원 경매 타입입니다")
            }
            if (openResult.containsKey("error")) {
                throw IllegalArgumentException(openResult["error"] as? String ?: "자원 경매 생성에 실패했습니다")
            }
            val auctionId = (openResult["auctionId"] as? Number)?.toLong()
                ?: throw IllegalStateException("생성된 경매 ID를 확인할 수 없습니다")
            val auction = auctionRepository.findById(auctionId).orElseThrow()
            return toMessage(auction)
        }

        val seller = generalRepository.findById(sellerId).orElse(null)
            ?: throw IllegalArgumentException("장수를 찾을 수 없습니다")
        if (seller.worldId != worldId) throw IllegalArgumentException("잘못된 월드 장수입니다")

        val auction = createAuction(sellerId, if (item.isBlank()) type else item, minPrice)
        return toMessage(auction)
    }

    @Transactional
    fun bid(id: Long, bidderId: Long, amount: Int): Map<String, Any>? {
        val auction = auctionRepository.findById(id).orElse(null) ?: return mapOf("error" to "경매가 없습니다")
        return if (auction.type == RESOURCE_AUCTION_TYPE) {
            bidResourceAuction(id, bidderId, amount)
        } else {
            placeBid(bidderId, id, amount)
        }
    }

    @Transactional(readOnly = true)
    fun getActiveResourceAuctionList(worldId: Long): Map<String, Any> {
        val active = auctionRepository.findByWorldIdAndStatusOrderByCreatedAtDesc(worldId, "open")
            .filter { it.type == RESOURCE_AUCTION_TYPE }
            .sortedBy { it.expiresAt }

        val buyRice = mutableListOf<Map<String, Any?>>()
        val sellRice = mutableListOf<Map<String, Any?>>()

        active.forEach { auction ->
            val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
            val row = mapOf(
                "id" to auction.id,
                "type" to auction.type,
                "subType" to (auction.subType ?: ""),
                "hostGeneralId" to auction.hostGeneralId,
                "hostName" to auction.hostName,
                "openDate" to auction.createdAt.toString(),
                "closeDate" to auction.expiresAt.toString(),
                "amount" to auction.amount,
                "startBidAmount" to auction.startBidAmount,
                "finishBidAmount" to auction.finishBidAmount,
                "highestBid" to highest?.let {
                    mapOf(
                        "amount" to it.amount,
                        "date" to it.createdAt.toString(),
                        "generalId" to it.bidderGeneralId,
                        "generalName" to (generalRepository.findById(it.bidderGeneralId).orElse(null)?.name ?: "Unknown"),
                    )
                },
                "closeDateExtensionCount" to auction.closeDateExtensionCount,
            )
            when (auction.subType) {
                RESOURCE_SUB_TYPE_BUY_RICE -> buyRice.add(row)
                RESOURCE_SUB_TYPE_SELL_RICE -> sellRice.add(row)
            }
        }

        return mapOf(
            "buyRice" to buyRice,
            "sellRice" to sellRice,
        )
    }

    @Transactional
    fun openBuyRiceAuction(
        worldId: Long,
        hostGeneralId: Long,
        amount: Int,
        closeTurnCnt: Int,
        startBidAmount: Int,
        finishBidAmount: Int,
    ): Map<String, Any> {
        return openResourceAuction(
            worldId = worldId,
            hostGeneralId = hostGeneralId,
            subType = RESOURCE_SUB_TYPE_BUY_RICE,
            amount = amount,
            closeTurnCnt = closeTurnCnt,
            startBidAmount = startBidAmount,
            finishBidAmount = finishBidAmount,
        )
    }

    @Transactional
    fun openSellRiceAuction(
        worldId: Long,
        hostGeneralId: Long,
        amount: Int,
        closeTurnCnt: Int,
        startBidAmount: Int,
        finishBidAmount: Int,
    ): Map<String, Any> {
        return openResourceAuction(
            worldId = worldId,
            hostGeneralId = hostGeneralId,
            subType = RESOURCE_SUB_TYPE_SELL_RICE,
            amount = amount,
            closeTurnCnt = closeTurnCnt,
            startBidAmount = startBidAmount,
            finishBidAmount = finishBidAmount,
        )
    }

    @Transactional
    fun bidResourceAuction(auctionId: Long, bidderId: Long, amount: Int): Map<String, Any> {
        val auction = auctionRepository.findById(auctionId).orElse(null)
            ?: return mapOf("error" to "경매가 없습니다")
        if (auction.type != RESOURCE_AUCTION_TYPE) return mapOf("error" to "자원 경매가 아닙니다")
        return placeResourceBid(auction, bidderId, amount)
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

    private fun openResourceAuction(
        worldId: Long,
        hostGeneralId: Long,
        subType: String,
        amount: Int,
        closeTurnCnt: Int,
        startBidAmount: Int,
        finishBidAmount: Int,
    ): Map<String, Any> {
        if (closeTurnCnt !in 1..24) return mapOf("error" to "종료기한은 1 ~ 24 턴 이어야 합니다")
        if (amount !in 100..10000) return mapOf("error" to "거래량은 100 ~ 10000 이어야 합니다")
        if (startBidAmount * 2 < amount || startBidAmount > amount * 2) {
            return mapOf("error" to "시작거래가는 거래량의 50% ~ 200% 이어야 합니다")
        }
        val minFinishByAmount = ceil(amount * 1.1).toInt()
        val minFinishByStart = ceil(startBidAmount * 1.1).toInt()
        if (finishBidAmount < minFinishByAmount || finishBidAmount > amount * 2) {
            return mapOf("error" to "즉시거래가는 거래량의 110% ~ 200% 이어야 합니다")
        }
        if (finishBidAmount < minFinishByStart) {
            return mapOf("error" to "즉시거래가는 시작거래가의 110% 이상이어야 합니다")
        }

        val host = generalRepository.findById(hostGeneralId).orElse(null)
            ?: return mapOf("error" to "장수를 찾을 수 없습니다")
        if (host.worldId != worldId) return mapOf("error" to "잘못된 월드 장수입니다")

        val world = worldStateRepository.findById(worldId.toShort()).orElse(null)
            ?: return mapOf("error" to "월드 정보를 찾을 수 없습니다")

        val hostResourceName = if (subType == RESOURCE_SUB_TYPE_BUY_RICE) "rice" else "gold"
        val hostMinimumReserve = if (hostResourceName == "rice") GENERAL_MINIMUM_RICE else GENERAL_MINIMUM_GOLD
        val hostCurrent = if (hostResourceName == "rice") host.rice else host.gold
        if (hostCurrent < amount + hostMinimumReserve) {
            val resLabel = if (hostResourceName == "rice") "쌀" else "금"
            return mapOf("error" to "기본 ${resLabel} ${hostMinimumReserve}은 거래할 수 없습니다")
        }

        if (hostResourceName == "rice") {
            host.rice -= amount
        } else {
            host.gold -= amount
        }
        generalRepository.save(host)

        val now = OffsetDateTime.now()
        val turnSeconds = world.tickSeconds.toLong().coerceAtLeast(60L)
        val closeDate = now.plusSeconds(turnSeconds * closeTurnCnt)
        val closeDateLimitSeconds = maxOf(300L, (turnSeconds / 2.0).toLong())

        val auction = Auction(
            worldId = worldId,
            sellerGeneralId = host.id,
            type = RESOURCE_AUCTION_TYPE,
            subType = subType,
            itemCode = subType,
            amount = amount,
            minPrice = startBidAmount,
            startBidAmount = startBidAmount,
            finishBidAmount = finishBidAmount,
            currentPrice = 0,
            buyerGeneralId = null,
            hostGeneralId = host.id,
            hostName = host.name,
            closeDateExtensionCount = 3,
            status = "open",
            createdAt = now,
            expiresAt = closeDate,
                meta = mutableMapOf<String, Any>(
                    "maxCloseDate" to closeDate.plusSeconds(closeDateLimitSeconds).toString(),
                    "closeTurnCnt" to closeTurnCnt,
                ),
        )
        val saved = auctionRepository.save(auction)
        return mapOf(
            "success" to true,
            "auctionId" to saved.id,
            "status" to saved.status,
            "closeDate" to saved.expiresAt.toString(),
        )
    }

    private fun placeResourceBid(auction: Auction, bidderId: Long, amount: Int): Map<String, Any> {
        if (auction.status != "open") return mapOf("error" to "종료된 경매입니다")
        if (auction.expiresAt <= OffsetDateTime.now()) {
            finalizeResourceAuction(auction)
            return mapOf("error" to "경매가 종료되었습니다")
        }
        if (auction.hostGeneralId == bidderId) return mapOf("error" to "자신의 경매에는 입찰할 수 없습니다")

        val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
        if (highest == null) {
            if (amount < auction.startBidAmount) return mapOf("error" to "시작거래가 이상 입찰해야 합니다")
        } else {
            if (amount <= highest.amount) return mapOf("error" to "현재 입찰가보다 높아야 합니다")
        }
        if (auction.finishBidAmount > 0 && amount > auction.finishBidAmount) {
            return mapOf("error" to "즉시거래가보다 높을 수 없습니다")
        }

        val bidder = generalRepository.findById(bidderId).orElse(null)
            ?: return mapOf("error" to "장수를 찾을 수 없습니다")
        if (bidder.worldId != auction.worldId) return mapOf("error" to "같은 월드에서만 입찰할 수 있습니다")

        val bidderResourceName = if (auction.subType == RESOURCE_SUB_TYPE_BUY_RICE) "gold" else "rice"
        val bidderBalance = if (bidderResourceName == "gold") bidder.gold else bidder.rice
        if (bidderBalance < amount) {
            val label = if (bidderResourceName == "gold") "금" else "쌀"
            return mapOf("error" to "${label}이 부족합니다")
        }

        if (bidderResourceName == "gold") {
            bidder.gold -= amount
        } else {
            bidder.rice -= amount
        }

        if (highest != null) {
            val prevBidder = generalRepository.findById(highest.bidderGeneralId).orElse(null)
            if (prevBidder != null) {
                if (bidderResourceName == "gold") {
                    prevBidder.gold += highest.amount
                } else {
                    prevBidder.rice += highest.amount
                }
                generalRepository.save(prevBidder)
            }
        }

        generalRepository.save(bidder)

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
        extendResourceAuctionCloseDate(auction)
        auctionRepository.save(auction)

        if (auction.finishBidAmount > 0 && amount >= auction.finishBidAmount) {
            auction.expiresAt = OffsetDateTime.now()
            val finalized = finalizeResourceAuction(auction)
            if (finalized.containsKey("error")) return finalized
            return finalized + mapOf("instantBuy" to true)
        }

        return mapOf(
            "success" to true,
            "auctionId" to auction.id,
            "currentBid" to amount,
            "closeDate" to auction.expiresAt.toString(),
            "closeDateExtensionCount" to auction.closeDateExtensionCount,
        )
    }

    private fun extendResourceAuctionCloseDate(auction: Auction) {
        if (auction.closeDateExtensionCount <= 0) return
        val world = worldStateRepository.findById(auction.worldId.toShort()).orElse(null) ?: return
        val turnSeconds = world.tickSeconds.toLong().coerceAtLeast(60L)
        val extensionSeconds = maxOf(60L, (turnSeconds / 6.0).toLong())
        val maxCloseDate = (auction.meta["maxCloseDate"] as? String)
            ?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }
            ?: auction.expiresAt.plusSeconds(maxOf(300L, (turnSeconds / 2.0).toLong()))

        val extended = auction.expiresAt.plusSeconds(extensionSeconds)
        if (extended <= auction.expiresAt) return
        val nextCloseDate = if (extended > maxCloseDate) maxCloseDate else extended
        if (nextCloseDate > auction.expiresAt) {
            auction.expiresAt = nextCloseDate
            auction.closeDateExtensionCount -= 1
        }
    }

    private fun finalizeResourceAuction(auction: Auction): Map<String, Any> {
        if (auction.status != "open") {
            return mapOf("success" to true, "auctionId" to auction.id, "status" to auction.status)
        }

        val host = generalRepository.findById(auction.hostGeneralId).orElse(null)
            ?: return mapOf("error" to "경매 주최자를 찾을 수 없습니다")
        val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)

        if (highest == null) {
            if (auction.subType == RESOURCE_SUB_TYPE_BUY_RICE) {
                host.rice += auction.amount
            } else {
                host.gold += auction.amount
            }
            generalRepository.save(host)
            auction.status = "expired"
            auctionRepository.save(auction)
            return mapOf("success" to true, "auctionId" to auction.id, "status" to auction.status)
        }

        val winner = generalRepository.findById(highest.bidderGeneralId).orElse(null)
        if (winner == null) {
            if (auction.subType == RESOURCE_SUB_TYPE_BUY_RICE) {
                host.rice += auction.amount
            } else {
                host.gold += auction.amount
            }
            generalRepository.save(host)
            auction.status = "failed"
            auctionRepository.save(auction)
            return mapOf("error" to "낙찰자를 찾을 수 없습니다")
        }

        if (auction.subType == RESOURCE_SUB_TYPE_BUY_RICE) {
            host.gold += highest.amount
            winner.rice += auction.amount
        } else {
            host.rice += highest.amount
            winner.gold += auction.amount
        }
        generalRepository.save(host)
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
            type = ITEM_AUCTION_TYPE,
            subType = null,
            itemCode = itemType,
            amount = 1,
            minPrice = startPrice,
            startBidAmount = startPrice,
            finishBidAmount = 0,
            currentPrice = startPrice,
            hostGeneralId = seller.id,
            hostName = seller.name,
            closeDateExtensionCount = 0,
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
        if (auction.type == RESOURCE_AUCTION_TYPE) {
            return placeResourceBid(auction, generalId, amount)
        }
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
        if (auction.type == RESOURCE_AUCTION_TYPE) {
            if (auction.hostGeneralId != generalId) return mapOf("error" to "본인 경매만 취소할 수 있습니다")

            val host = generalRepository.findById(generalId).orElse(null)
                ?: return mapOf("error" to "장수를 찾을 수 없습니다")
            if (auction.subType == RESOURCE_SUB_TYPE_BUY_RICE) {
                host.rice += auction.amount
            } else {
                host.gold += auction.amount
            }
            generalRepository.save(host)

            val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
            if (highest != null) {
                val highestBidder = generalRepository.findById(highest.bidderGeneralId).orElse(null)
                if (highestBidder != null) {
                    if (auction.subType == RESOURCE_SUB_TYPE_BUY_RICE) {
                        highestBidder.gold += highest.amount
                    } else {
                        highestBidder.rice += highest.amount
                    }
                    generalRepository.save(highestBidder)
                }
            }

            auction.status = "cancelled"
            auctionRepository.save(auction)
            return mapOf("success" to true, "auctionId" to auction.id, "status" to auction.status)
        }

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
        if (auction.type == RESOURCE_AUCTION_TYPE) {
            return finalizeResourceAuction(auction)
        }
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
            }
            generalRepository.save(seller)
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
        val sellerName = generalRepository.findById(auction.sellerGeneralId).orElse(null)?.name ?: auction.hostName.ifBlank { "Unknown" }
        val highest = auctionBidRepository.findTopByAuctionIdOrderByAmountDesc(auction.id)
        val highestBidderName = highest?.bidderGeneralId?.let { generalRepository.findById(it).orElse(null)?.name }
        return Message(
            id = auction.id,
            worldId = auction.worldId,
            mailboxCode = "auction",
            messageType = if (auction.type == RESOURCE_AUCTION_TYPE) (auction.subType ?: auction.type) else auction.itemCode,
            srcId = auction.sellerGeneralId,
            payload = mutableMapOf<String, Any>(
                "type" to auction.type,
                "subType" to (auction.subType ?: ""),
                "sellerId" to auction.sellerGeneralId,
                "sellerName" to sellerName,
                "hostGeneralId" to auction.hostGeneralId,
                "hostName" to auction.hostName.ifBlank { sellerName },
                "item" to auction.itemCode,
                "amount" to auction.amount,
                "minPrice" to auction.minPrice,
                "startBidAmount" to auction.startBidAmount,
                "finishBidAmount" to auction.finishBidAmount,
                "currentBid" to auction.currentPrice,
                "bidderId" to (highest?.bidderGeneralId ?: 0L),
                "currentBidderId" to (highest?.bidderGeneralId ?: 0L),
                "currentBidderName" to (highestBidderName ?: ""),
                "bidCount" to auctionBidRepository.findByAuctionId(auction.id).size,
                "state" to auction.status,
                "status" to auction.status,
                "expiresAt" to auction.expiresAt.toString(),
                "endTime" to auction.expiresAt.toString(),
                "remainCloseDateExtensionCnt" to auction.closeDateExtensionCount,
            ),
        )
    }
}
