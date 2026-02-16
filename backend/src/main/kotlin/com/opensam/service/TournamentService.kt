package com.opensam.service

import com.opensam.dto.BetEntryResponse
import com.opensam.dto.BettingInfoResponse
import com.opensam.dto.TournamentBracketMatchResponse
import com.opensam.dto.TournamentInfoResponse
import com.opensam.repository.GeneralRepository
import com.opensam.repository.WorldStateRepository
import org.springframework.stereotype.Service

@Service
class TournamentService(
    private val generalRepository: GeneralRepository,
    private val worldStateRepository: WorldStateRepository,
) {
    fun getTournament(worldId: Long): TournamentInfoResponse? {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return null
        val state = (world.meta["tournamentState"] as? Number)?.toInt() ?: 0

        val participants = (world.meta["tournamentParticipants"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toLong() }
            ?: emptyList()

        val bracket = (world.meta["tournamentBracket"] as? List<*>)
            ?.mapNotNull { raw ->
                val row = raw as? Map<*, *> ?: return@mapNotNull null
                val round = (row["round"] as? Number)?.toInt() ?: return@mapNotNull null
                val match = (row["match"] as? Number)?.toInt() ?: return@mapNotNull null
                val p1 = (row["p1"] as? Number)?.toLong() ?: return@mapNotNull null
                val p2 = (row["p2"] as? Number)?.toLong() ?: return@mapNotNull null
                val winner = (row["winner"] as? Number)?.toLong()
                TournamentBracketMatchResponse(round, match, p1, p2, winner)
            }
            ?: emptyList()

        return TournamentInfoResponse(
            state = state,
            bracket = bracket,
            participants = participants,
        )
    }

    fun register(worldId: Long, generalId: Long): Map<String, Any>? {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return null
        val participants = ((world.meta["tournamentParticipants"] as? List<*>)
            ?.mapNotNull { (it as? Number)?.toLong() }
            ?: emptyList()).toMutableList()
        if (generalId in participants) return mapOf("error" to "이미 등록됨")
        participants.add(generalId)
        world.meta["tournamentParticipants"] = participants
        worldStateRepository.save(world)
        return mapOf("success" to true)
    }

    fun getBetting(worldId: Long): BettingInfoResponse? {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return null

        val bets = (world.meta["bets"] as? List<*>)
            ?.mapNotNull { raw ->
                val row = raw as? Map<*, *> ?: return@mapNotNull null
                val generalId = (row["generalId"] as? Number)?.toLong() ?: return@mapNotNull null
                val targetId = (row["targetId"] as? Number)?.toLong() ?: return@mapNotNull null
                val amount = (row["amount"] as? Number)?.toInt() ?: return@mapNotNull null
                BetEntryResponse(generalId, targetId, amount)
            }
            ?: emptyList()

        val odds = mutableMapOf<String, Double>()
        val rawOdds = world.meta["bettingOdds"] as? Map<*, *> ?: emptyMap<Any, Any>()
        for ((k, v) in rawOdds) {
            if (k is String) {
                val value = (v as? Number)?.toDouble() ?: continue
                odds[k] = value
            }
        }

        return BettingInfoResponse(
            bets = bets,
            odds = odds,
        )
    }

    fun placeBet(worldId: Long, generalId: Long, targetId: Long, amount: Int): Map<String, Any>? {
        val general = generalRepository.findById(generalId).orElse(null) ?: return null
        if (general.gold < amount) return mapOf("error" to "금 부족")
        general.gold -= amount
        generalRepository.save(general)
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return null

        val bets = mutableListOf<Map<String, Any>>()
        val existing = world.meta["bets"] as? List<*> ?: emptyList<Any>()
        for (raw in existing) {
            val row = raw as? Map<*, *> ?: continue
            val mapped = mutableMapOf<String, Any>()
            val eGeneralId = (row["generalId"] as? Number)?.toLong() ?: continue
            val eTargetId = (row["targetId"] as? Number)?.toLong() ?: continue
            val eAmount = (row["amount"] as? Number)?.toInt() ?: continue
            mapped["generalId"] = eGeneralId
            mapped["targetId"] = eTargetId
            mapped["amount"] = eAmount
            bets.add(mapped)
        }

        bets.add(mapOf("generalId" to generalId, "targetId" to targetId, "amount" to amount))
        world.meta["bets"] = bets
        worldStateRepository.save(world)
        return mapOf("success" to true)
    }
}
