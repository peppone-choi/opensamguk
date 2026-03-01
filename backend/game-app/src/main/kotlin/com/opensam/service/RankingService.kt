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

    fun hallOfFameOptions(worldId: Long): Map<String, Any> {
        val fames = messageRepository.findByWorldIdAndMailboxCodeOrderBySentAtDesc(worldId, "hall_of_fame")
        val seasons = fames
            .mapNotNull { msg ->
                val scenario = msg.payload["scenarioCode"] as? String ?: return@mapNotNull null
                val label = msg.payload["scenarioLabel"] as? String ?: scenario
                mapOf("code" to scenario, "label" to label)
            }
            .distinctBy { it["code"] }

        return mapOf(
            "seasons" to listOf(
                mapOf(
                    "id" to worldId,
                    "label" to "전체",
                    "scenarios" to seasons,
                )
            )
        )
    }

    fun uniqueItemOwners(worldId: Long): List<Map<String, Any?>> {
        val generals = generalRepository.findByWorldId(worldId)
        val slots = listOf(
            "weapon" to "무기",
            "book" to "서적",
            "horse" to "명마",
            "item" to "도구",
        )

        return slots.flatMap { (slot, slotLabel) ->
            generals
                .filter {
                    val code = when (slot) {
                        "weapon" -> it.weaponCode
                        "book" -> it.bookCode
                        "horse" -> it.horseCode
                        "item" -> it.itemCode
                        else -> "None"
                    }
                    code != "None" && code.isNotBlank()
                }
                .map { gen ->
                    val code = when (slot) {
                        "weapon" -> gen.weaponCode
                        "book" -> gen.bookCode
                        "horse" -> gen.horseCode
                        else -> gen.itemCode
                    }
                    mapOf(
                        "slot" to slot,
                        "slotLabel" to slotLabel,
                        "generalId" to gen.id,
                        "generalName" to gen.name,
                        "nationId" to gen.nationId,
                        "nationName" to "",
                        "nationColor" to "",
                        "itemName" to code,
                        "itemGrade" to "unique",
                    )
                }
        }
    }
}
