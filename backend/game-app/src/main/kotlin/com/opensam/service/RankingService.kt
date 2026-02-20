package com.opensam.service

import com.opensam.dto.BestGeneralResponse
import com.opensam.repository.GeneralRepository
import com.opensam.repository.MessageRepository
import org.springframework.stereotype.Service

@Service
class RankingService(
    private val generalRepository: GeneralRepository,
    private val messageRepository: MessageRepository,
) {
    fun bestGenerals(worldId: Long, sortBy: String, limit: Int): List<BestGeneralResponse> {
        val generals = generalRepository.findByWorldId(worldId)
        val sorted = when (sortBy) {
            "leadership" -> generals.sortedByDescending { it.leadership }
            "strength" -> generals.sortedByDescending { it.strength }
            "intel" -> generals.sortedByDescending { it.intel }
            "politics" -> generals.sortedByDescending { it.politics }
            "charm" -> generals.sortedByDescending { it.charm }
            "dedication" -> generals.sortedByDescending { it.dedication }
            "crew" -> generals.sortedByDescending { it.crew }
            else -> generals.sortedByDescending { it.experience }
        }
        return sorted.take(limit).map { BestGeneralResponse.from(it) }
    }

    fun hallOfFame(worldId: Long) =
        messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "hall_of_fame")
}
