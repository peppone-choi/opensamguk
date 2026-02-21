package com.opensam.service

import com.opensam.dto.BetEntryResponse
import com.opensam.dto.BettingInfoResponse
import com.opensam.dto.TournamentBracketMatchResponse
import com.opensam.dto.TournamentInfoResponse
import com.opensam.engine.TournamentBattle
import com.opensam.entity.Tournament
import com.opensam.repository.GeneralRepository
import com.opensam.repository.TournamentRepository
import com.opensam.repository.WorldStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TournamentService(
    private val generalRepository: GeneralRepository,
    private val tournamentRepository: TournamentRepository,
    private val worldStateRepository: WorldStateRepository,
) {
    companion object {
        private const val STATE_REGISTER = 1
        private const val STATE_BRACKET = 2
        private const val STATE_FINISHED = 4
    }

    @Transactional(readOnly = true)
    fun getTournament(worldId: Long): TournamentInfoResponse? {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return null
        val state = (world.meta["tournamentState"] as? Number)?.toInt() ?: 0

        val entries = tournamentRepository.findByWorldIdOrderByRoundAscBracketPositionAsc(worldId)
        val participants = entries.filter { it.round.toInt() == 0 }.map { it.generalId }
        val bracket = entries
            .filter { it.round.toInt() > 0 }
            .map {
                TournamentBracketMatchResponse(
                    round = it.round.toInt(),
                    match = it.bracketPosition.toInt() / 2,
                    p1 = it.generalId,
                    p2 = it.opponentId ?: 0,
                    winner = if (it.result.toInt() == 1) it.generalId else null,
                )
            }

        return TournamentInfoResponse(
            state = state,
            bracket = bracket,
            participants = participants,
        )
    }

    @Transactional
    fun register(worldId: Long, generalId: Long): Map<String, Any>? {
        return registerParticipant(worldId, generalId)
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

    @Transactional
    fun createTournament(worldId: Long, type: Int): Map<String, Any>? {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return null
        tournamentRepository.deleteByWorldId(worldId)

        world.meta["tournamentState"] = STATE_REGISTER
        world.meta["tournamentType"] = type
        world.meta["tournamentRound"] = 0
        world.meta["tournamentWinnerId"] = 0L
        world.meta["tournamentParticipants"] = emptyList<Long>()
        world.meta["tournamentBracket"] = emptyList<Map<String, Any>>()
        worldStateRepository.save(world)

        return mapOf("success" to true, "worldId" to worldId, "type" to type, "state" to STATE_REGISTER)
    }

    @Transactional
    fun registerParticipant(tournamentId: Long, generalId: Long): Map<String, Any>? {
        val world = worldStateRepository.findById(tournamentId.toShort()).orElse(null) ?: return null
        val state = (world.meta["tournamentState"] as? Number)?.toInt() ?: 0
        if (state != STATE_REGISTER) return mapOf("error" to "등록 기간이 아닙니다")

        val general = generalRepository.findById(generalId).orElse(null) ?: return mapOf("error" to "장수가 없습니다")
        if (general.worldId != tournamentId) return mapOf("error" to "같은 월드의 장수만 참가할 수 있습니다")

        val exists = tournamentRepository.findByWorldIdAndRound(tournamentId, 0).any { it.generalId == generalId }
        if (exists) return mapOf("error" to "이미 등록됨")

        tournamentRepository.save(
            Tournament(
                worldId = tournamentId,
                generalId = generalId,
                round = 0,
                bracketPosition = 0,
                result = 0,
            )
        )
        syncWorldTournamentMeta(tournamentId)
        return mapOf("success" to true, "generalId" to generalId)
    }

    @Transactional
    fun startTournament(tournamentId: Long): Map<String, Any>? {
        val world = worldStateRepository.findById(tournamentId.toShort()).orElse(null) ?: return null
        val participants = tournamentRepository.findByWorldIdAndRound(tournamentId, 0)
        if (participants.size < 2) return mapOf("error" to "참가자가 부족합니다")

        val seeded = participants.shuffled().map { it.generalId }.toMutableList()
        var bracketSize = 1
        while (bracketSize < seeded.size) bracketSize *= 2
        while (seeded.size < bracketSize) seeded.add(0L)

        for (idx in seeded.indices step 2) {
            val p1 = seeded[idx]
            val p2 = seeded[idx + 1]
            if (p1 == 0L) continue

            tournamentRepository.save(
                Tournament(
                    worldId = tournamentId,
                    generalId = p1,
                    round = 1,
                    bracketPosition = idx.toShort(),
                    opponentId = if (p2 == 0L) null else p2,
                    result = if (p2 == 0L) 1 else 0,
                )
            )
            if (p2 != 0L) {
                tournamentRepository.save(
                    Tournament(
                        worldId = tournamentId,
                        generalId = p2,
                        round = 1,
                        bracketPosition = (idx + 1).toShort(),
                        opponentId = p1,
                        result = 0,
                    )
                )
            }
        }

        world.meta["tournamentState"] = STATE_BRACKET
        world.meta["tournamentRound"] = 1
        worldStateRepository.save(world)
        syncWorldTournamentMeta(tournamentId)
        return mapOf("success" to true, "state" to STATE_BRACKET, "round" to 1)
    }

    @Transactional
    fun advanceRound(tournamentId: Long): Map<String, Any>? {
        val world = worldStateRepository.findById(tournamentId.toShort()).orElse(null) ?: return null
        val state = (world.meta["tournamentState"] as? Number)?.toInt() ?: 0
        if (state != STATE_BRACKET) return mapOf("error" to "대회가 진행 중이 아닙니다")

        val round = (world.meta["tournamentRound"] as? Number)?.toInt() ?: 1
        val current = tournamentRepository.findByWorldIdAndRoundOrderByBracketPositionAsc(tournamentId, round.toShort())
        if (current.isEmpty()) return mapOf("error" to "현재 라운드 데이터가 없습니다")

        val nextWinners = mutableListOf<Long>()
        val grouped = current.groupBy { it.bracketPosition.toInt() / 2 }
        for ((_, pair) in grouped) {
            val attackerRow = pair.minByOrNull { it.bracketPosition } ?: continue
            val defenderRow = pair.maxByOrNull { it.bracketPosition }

            if (attackerRow.opponentId == null || defenderRow == null) {
                attackerRow.result = 1
                tournamentRepository.save(attackerRow)
                nextWinners.add(attackerRow.generalId)
                continue
            }

            val attackerGeneral = generalRepository.findById(attackerRow.generalId).orElse(null)
            val defenderGeneral = generalRepository.findById(defenderRow.generalId).orElse(null)
            if (attackerGeneral == null || defenderGeneral == null) continue

            val battleResult = TournamentBattle.resolveTournamentBattle(
                TournamentBattle.TournamentBattleInput(
                    type = (world.meta["tournamentType"] as? Number)?.toInt() ?: TournamentBattle.TOURNAMENT_TOTAL,
                    battleType = 1,
                    attacker = TournamentBattle.TournamentParticipant(
                        id = attackerGeneral.id,
                        name = attackerGeneral.name,
                        stats = TournamentBattle.TournamentStats(
                            leadership = attackerGeneral.leadership.toDouble(),
                            strength = attackerGeneral.strength.toDouble(),
                            intel = attackerGeneral.intel.toDouble(),
                        ),
                        level = attackerGeneral.expLevel.toInt(),
                    ),
                    defender = TournamentBattle.TournamentParticipant(
                        id = defenderGeneral.id,
                        name = defenderGeneral.name,
                        stats = TournamentBattle.TournamentStats(
                            leadership = defenderGeneral.leadership.toDouble(),
                            strength = defenderGeneral.strength.toDouble(),
                            intel = defenderGeneral.intel.toDouble(),
                        ),
                        level = defenderGeneral.expLevel.toInt(),
                    ),
                    context = TournamentBattle.TournamentBattleContext(
                        openYear = world.currentYear.toInt(),
                        openMonth = world.currentMonth.toInt(),
                        stage = round,
                        phase = 0,
                        matchIndex = attackerRow.bracketPosition.toInt() / 2,
                    ),
                    baseSeed = (world.config["hiddenSeed"] as? String) ?: "tournament",
                )
            )

            val winnerId = battleResult.winnerId ?: attackerGeneral.id
            attackerRow.result = if (winnerId == attackerGeneral.id) 1 else -1
            defenderRow.result = if (winnerId == defenderGeneral.id) 1 else -1
            tournamentRepository.save(attackerRow)
            tournamentRepository.save(defenderRow)
            nextWinners.add(winnerId)
        }

        if (nextWinners.size <= 1) {
            world.meta["tournamentState"] = STATE_FINISHED
            world.meta["tournamentWinnerId"] = nextWinners.firstOrNull() ?: 0L
            worldStateRepository.save(world)
            syncWorldTournamentMeta(tournamentId)
            return mapOf("success" to true, "finished" to true, "winnerId" to (nextWinners.firstOrNull() ?: 0L))
        }

        val nextRound = round + 1
        for (idx in nextWinners.indices step 2) {
            val p1 = nextWinners[idx]
            val p2 = nextWinners.getOrNull(idx + 1)
            tournamentRepository.save(
                Tournament(
                    worldId = tournamentId,
                    generalId = p1,
                    round = nextRound.toShort(),
                    bracketPosition = idx.toShort(),
                    opponentId = p2,
                    result = if (p2 == null) 1 else 0,
                )
            )
            if (p2 != null) {
                tournamentRepository.save(
                    Tournament(
                        worldId = tournamentId,
                        generalId = p2,
                        round = nextRound.toShort(),
                        bracketPosition = (idx + 1).toShort(),
                        opponentId = p1,
                        result = 0,
                    )
                )
            }
        }

        world.meta["tournamentRound"] = nextRound
        worldStateRepository.save(world)
        syncWorldTournamentMeta(tournamentId)
        return mapOf("success" to true, "finished" to false, "nextRound" to nextRound)
    }

    @Transactional
    fun finalizeTournament(tournamentId: Long): Map<String, Any>? {
        val world = worldStateRepository.findById(tournamentId.toShort()).orElse(null) ?: return null
        val winnerId = (world.meta["tournamentWinnerId"] as? Number)?.toLong() ?: 0L
        if (winnerId == 0L) return mapOf("error" to "우승자가 정해지지 않았습니다")

        val winner = generalRepository.findById(winnerId).orElse(null)
            ?: return mapOf("error" to "우승 장수를 찾을 수 없습니다")

        val finalRound = tournamentRepository.findByWorldId(world.id.toLong()).maxOfOrNull { it.round.toInt() } ?: 1
        val finalists = tournamentRepository.findByWorldIdAndRound(world.id.toLong(), finalRound.toShort())
        val runnerUpId = finalists.firstOrNull { it.generalId != winnerId }?.generalId
        val runnerUp = runnerUpId?.let { generalRepository.findById(it).orElse(null) }

        winner.gold += 5000
        winner.dedication += 100
        generalRepository.save(winner)

        if (runnerUp != null) {
            runnerUp.gold += 2500
            runnerUp.dedication += 50
            generalRepository.save(runnerUp)
        }

        world.meta["tournamentState"] = 0
        worldStateRepository.save(world)

        return mapOf(
            "success" to true,
            "winnerId" to winner.id,
            "runnerUpId" to (runnerUp?.id ?: 0L),
            "winnerRewardGold" to 5000,
            "runnerUpRewardGold" to if (runnerUp != null) 2500 else 0,
        )
    }

    /**
     * 턴 파이프라인에서 호출: 자동 진행 중인 토너먼트의 다음 라운드를 처리한다.
     * legacy processTournament 패러티 — 수동 토너먼트(tnmt_auto=false)는 무시.
     */
    @Transactional
    fun processTournamentTurn(worldId: Long) {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return
        val state = (world.meta["tournamentState"] as? Number)?.toInt() ?: 0
        val isAuto = (world.meta["tournamentAuto"] as? Boolean) ?: true

        // 수동일 때는 무시
        if (!isAuto) return

        when (state) {
            STATE_REGISTER -> {
                // 등록 마감 → 대진표 생성
                startTournament(worldId)
            }
            STATE_BRACKET -> {
                // 본선 진행: 한 라운드 자동 진행
                advanceRound(worldId)
            }
            STATE_FINISHED -> {
                // 결과 확정 → 보상 지급 및 토너먼트 종료
                finalizeTournament(worldId)
            }
            // state == 0: 토너먼트 없음 → 무시
        }
    }

    private fun syncWorldTournamentMeta(worldId: Long) {
        val world = worldStateRepository.findById(worldId.toShort()).orElse(null) ?: return
        val entries = tournamentRepository.findByWorldIdOrderByRoundAscBracketPositionAsc(worldId)

        val participants = entries.filter { it.round.toInt() == 0 }.map { it.generalId }
        val bracket = entries
            .filter { it.round.toInt() > 0 }
            .map {
                mapOf(
                    "round" to it.round.toInt(),
                    "match" to it.bracketPosition.toInt() / 2,
                    "p1" to it.generalId,
                    "p2" to (it.opponentId ?: 0L),
                    "winner" to if (it.result.toInt() == 1) it.generalId else null,
                )
            }

        world.meta["tournamentParticipants"] = participants
        world.meta["tournamentBracket"] = bracket
        worldStateRepository.save(world)
    }
}
