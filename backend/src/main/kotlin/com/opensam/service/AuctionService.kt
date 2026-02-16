package com.opensam.service

import com.opensam.entity.Message
import com.opensam.repository.GeneralRepository
import com.opensam.repository.MessageRepository
import org.springframework.stereotype.Service

@Service
class AuctionService(
    private val messageRepository: MessageRepository,
    private val generalRepository: GeneralRepository,
) {
    fun listAuctions(worldId: Long): List<Message> {
        return messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "auction")
    }

    fun createAuction(worldId: Long, type: String, sellerId: Long, item: String, amount: Int, minPrice: Int): Message {
        return messageRepository.save(Message(
            worldId = worldId,
            mailboxCode = "auction",
            messageType = type,
            srcId = sellerId,
            payload = mutableMapOf(
                "item" to item,
                "amount" to amount,
                "minPrice" to minPrice,
                "currentBid" to 0,
                "bidderId" to 0L,
                "state" to "open",
            ),
        ))
    }

    fun bid(id: Long, bidderId: Long, amount: Int): Map<String, Any>? {
        val auction = messageRepository.findById(id).orElse(null) ?: return null
        val currentBid = (auction.payload["currentBid"] as? Number)?.toInt() ?: 0
        if (amount <= currentBid) return mapOf("error" to "현재 입찰가보다 높아야 합니다")
        auction.payload["currentBid"] = amount
        auction.payload["bidderId"] = bidderId
        messageRepository.save(auction)
        return mapOf("success" to true, "currentBid" to amount)
    }
}
