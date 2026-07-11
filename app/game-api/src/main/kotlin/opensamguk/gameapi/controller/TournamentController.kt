package opensamguk.gameapi.controller

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.TournamentAdminEntry
import opensamguk.gameapi.dto.TournamentAdminMatch
import opensamguk.gameapi.dto.TournamentBettingCandidate
import opensamguk.gameapi.dto.TournamentBracketMatch
import opensamguk.gameapi.dto.TournamentFightLog
import opensamguk.gameapi.dto.TournamentGroupStage
import opensamguk.gameapi.dto.TournamentRankingBoard
import opensamguk.gameapi.dto.TournamentResponse
import opensamguk.gameapi.dto.TournamentStandingRow
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.logic.tournament.TournamentEntry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/tournament")
class TournamentController(
    private val gameKv: GameKvReadRepository,
    private val objectMapper: ObjectMapper,
    private val reserve: CommandReserveService? = null,
    private val resolver: GeneralResolver? = null,
) {
    private val permissionDeniedReason = "권한이 부족합니다. 수뇌부가 아닙니다."
    private val knockoutStages = listOf(
        KnockoutStage(round = 16, sourceBase = 20, matchCount = 8, targetBase = 30),
        KnockoutStage(round = 8, sourceBase = 30, matchCount = 4, targetBase = 40),
        KnockoutStage(round = 4, sourceBase = 40, matchCount = 2, targetBase = 50),
        KnockoutStage(round = 2, sourceBase = 50, matchCount = 1, targetBase = 60),
    )

    private fun kvInt(key: String, default: Int): Int {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key)
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "global", key)
            ?: return default
        return runCatching { objectMapper.readTree(row.value).asInt(default) }.getOrDefault(default)
    }

    private fun kvText(key: String, default: String): String {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key)
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "global", key)
            ?: return default
        return runCatching {
            val node = objectMapper.readTree(row.value)
            if (node.isTextual) node.asText() else node.toString()
        }.getOrDefault(default)
    }

    @GetMapping
    fun tournament(): ResponseEntity<TournamentResponse> {
        val state = kvInt("tournament", 0)
        val tnmtType = kvInt("tnmt_type", 0)
        val turnTerm = kvInt("turnterm", 0)
        val tnmtMsg = kvText("tnmt_msg", "")
        val lastBettingId = kvInt("last_tournament_betting_id", 0)
        val entries = tournamentEntries()
        val logs = tournamentLogs()

        val rankings = F4StateText.RANKING_TYPES.map { TournamentRankingBoard(type = it, rows = emptyList()) }
        val entrants = standings(entries, tnmtType)
        val bracket = bracket(entries)
        val fightLogs = logs.entries.sortedBy { it.key }.map { TournamentFightLog(it.key, it.value) }
        val bettingCandidates = bettingCandidates(lastBettingId, entries)
        val adminEntries = adminEntries(entries)
        val adminMatches = adminMatches(entries)

        return ResponseEntity.ok(
            TournamentResponse(
                state = state,
                tnmtType = tnmtType,
                tnmtTypeText = F4StateText.tournamentTypeText(tnmtType),
                tnmtMsg = tnmtMsg,
                turnTerm = turnTerm,
                entrants = entrants,
                bracket = bracket,
                rankings = rankings,
                fightLogs = fightLogs,
                bettingCandidates = bettingCandidates,
                entries = adminEntries,
                matches = adminMatches,
            ),
        )
    }

    @PostMapping("/start")
    fun start(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) request: TournamentStartRequest?,
    ): ResponseEntity<TournamentCommandAcceptedResponse> {
        authorizeTournamentAdmin(userId, generalId)?.let { return it }
        val service = reserve ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            TournamentCommandAcceptedResponse(status = "UNAVAILABLE", requestId = "", turnIdx = 0),
        )
        val tournamentType = request?.type ?: request?.tournamentType ?: 0
        val result = service.reserve(
            generalId = generalId,
            actionCode = "tournamentStart",
            turnIdx = 0,
            argJson = objectMapper.writeValueAsString(mapOf("type" to tournamentType)),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            TournamentCommandAcceptedResponse(status = "AVAILABLE", requestId = result.requestId, turnIdx = result.turnIdx),
        )
    }

    @PostMapping("/reset")
    fun reset(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
    ): ResponseEntity<TournamentCommandAcceptedResponse> {
        authorizeTournamentAdmin(userId, generalId)?.let { return it }
        val service = reserve ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            TournamentCommandAcceptedResponse(status = "UNAVAILABLE", requestId = "", turnIdx = 0),
        )
        val result = service.reserve(
            generalId = generalId,
            actionCode = "tournamentReset",
            turnIdx = 0,
            argJson = null,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            TournamentCommandAcceptedResponse(status = "AVAILABLE", requestId = result.requestId, turnIdx = result.turnIdx),
        )
    }

    private fun authorizeTournamentAdmin(
        userId: Long?,
        requestedGeneralId: Int,
    ): ResponseEntity<TournamentCommandAcceptedResponse>? {
        val guard = resolver ?: return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            TournamentCommandAcceptedResponse(status = "UNAVAILABLE", requestId = "", turnIdx = 0),
        )
        val resolved = userId?.let { guard.resolve(it) }
            ?: return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                TournamentCommandAcceptedResponse(status = "FORBIDDEN", requestId = "", turnIdx = 0),
            )
        if (resolved.general.id != requestedGeneralId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                TournamentCommandAcceptedResponse(status = "FORBIDDEN", requestId = "", turnIdx = 0),
            )
        }
        if (resolved.permission < 2) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(
                TournamentCommandAcceptedResponse(
                    status = "BLOCKED",
                    requestId = "",
                    turnIdx = 0,
                    reason = permissionDeniedReason,
                ),
            )
        }
        return null
    }

    private fun tournamentEntries(): List<TournamentEntry> {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", "tournament_entries")
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "global", "tournament_entries")
            ?: return emptyList()
        return runCatching {
            objectMapper.readTree(row.value).map { node ->
                TournamentEntry(
                    id = node.path("id").asInt(),
                    npc = node.path("npc").asInt(),
                    name = node.path("name").asText(""),
                    leadership = node.path("leadership").asInt(),
                    strength = node.path("strength").asInt(),
                    intel = node.path("intel").asInt(),
                    level = node.path("level").asInt(),
                    group = node.path("group").asInt(),
                    groupNo = node.path("groupNo").asInt(),
                    win = node.path("win").asInt(),
                    draw = node.path("draw").asInt(),
                    lose = node.path("lose").asInt(),
                    goal = node.path("goal").asInt(),
                    promote = node.path("promote").asInt(),
                    seq = node.path("seq").asInt(),
                    horse = node.path("horse").asText("None"),
                    weapon = node.path("weapon").asText("None"),
                    book = node.path("book").asText("None"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun tournamentLogs(): Map<Int, List<String>> {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", "tournament_logs")
            ?: gameKv.findByTableAndNamespaceAndKey("game_env", "global", "tournament_logs")
            ?: return emptyMap()
        return runCatching {
            objectMapper.readValue(row.value, object : TypeReference<Map<Int, List<String>>>() {})
        }.getOrDefault(emptyMap())
    }

    private fun standings(entries: List<TournamentEntry>, tournamentType: Int): List<TournamentStandingRow> =
        listOf(TournamentGroupStage.MAIN to 10, TournamentGroupStage.PRELIMINARY to 0).flatMap { (stage, groupBase) ->
            (0 until 8).flatMap { groupNo ->
                entries.asSequence()
                    .filter { it.group == groupBase + groupNo }
                    .sortedWith(compareByDescending<TournamentEntry> { it.point }.thenByDescending { it.goal }.thenBy { it.seq })
                    .mapIndexed { index, entry ->
                        TournamentStandingRow(
                            generalId = entry.id,
                            npc = entry.npc,
                            generalName = entry.name,
                            stage = stage,
                            groupNo = groupNo,
                            groupRank = index + 1,
                            ability = tournamentAbility(entry, tournamentType),
                            games = entry.games,
                            win = entry.win,
                            draw = entry.draw,
                            lose = entry.lose,
                            points = entry.point,
                            goalDifference = entry.goal,
                            promoted = entry.promote > 0,
                        )
                    }
                    .toList()
            }
        }

    private fun tournamentAbility(entry: TournamentEntry, tournamentType: Int): Int = when (tournamentType) {
        1 -> entry.leadership
        2 -> entry.strength
        3 -> entry.intel
        else -> entry.total
    }

    private fun bracket(entries: List<TournamentEntry>): List<TournamentBracketMatch> {
        val projectedSlots = entries.asSequence()
            .filter { it.group >= 20 && it.groupNo in 0..1 }
            .groupBy { it.group }
            .mapValues { (_, rows) -> rows.associateBy { it.groupNo }.toMutableMap() }
            .toMutableMap()
        val matches = linkedMapOf<Pair<Int, Int>, TournamentBracketMatch>()

        for (stage in knockoutStages.asReversed()) {
            for (matchIdx in 0 until stage.matchCount) {
                val sourceGroup = stage.sourceBase + matchIdx
                val sourceSlots = projectedSlots.getOrPut(sourceGroup) { linkedMapOf() }
                val targetGroup = stage.targetBase + matchIdx / 2
                val winner = projectedSlots[targetGroup]?.get(matchIdx % 2)
                if (sourceSlots.isEmpty() && winner == null) continue

                if (winner != null && sourceSlots.values.none { it.id == winner.id }) {
                    val occupiedSlot = sourceSlots.entries.singleOrNull()?.key
                    if (occupiedSlot != null) sourceSlots[1 - occupiedSlot] = winner
                }
                val left = sourceSlots[0]
                val right = sourceSlots[1]
                matches[stage.round to matchIdx] = TournamentBracketMatch(
                    round = stage.round,
                    matchIdx = matchIdx,
                    leftGeneralId = left?.id,
                    leftName = left?.name,
                    rightGeneralId = right?.id,
                    rightName = right?.name,
                    winnerGeneralId = winner?.id,
                    winnerName = winner?.name,
                )
            }
        }

        return knockoutStages.flatMap { stage ->
            (0 until stage.matchCount).mapNotNull { matchIdx -> matches[stage.round to matchIdx] }
        }
    }

    private fun adminEntries(entries: List<TournamentEntry>): List<TournamentAdminEntry> =
        entries.sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.groupNo }.thenBy { it.seq })
            .mapIndexed { index, entry ->
                TournamentAdminEntry(
                    id = entry.seq.takeIf { it > 0 } ?: (index + 1),
                    generalId = entry.id,
                    generalName = entry.name,
                    nationId = 0,
                    nationName = "",
                    round = tournamentRound(entry.group),
                    seed = entry.seq.takeIf { it > 0 } ?: (index + 1),
                    eliminated = entry.group < 20 && entry.promote == 0 && entry.games > 0,
                )
            }

    private fun adminMatches(entries: List<TournamentEntry>): List<TournamentAdminMatch> =
        entries.filter { it.group >= 20 }
            .groupBy { it.group }
            .toSortedMap()
            .mapNotNull { (group, rows) ->
                val ordered = rows.sortedBy { it.groupNo }
                val left = ordered.getOrNull(0) ?: return@mapNotNull null
                val right = ordered.getOrNull(1) ?: return@mapNotNull null
                val winner = ordered.firstOrNull { it.win > 0 && it.groupNo in 0..1 }
                TournamentAdminMatch(
                    id = group,
                    round = tournamentRound(group),
                    bracket = group.toString(),
                    attackerId = left.id,
                    attackerName = left.name,
                    defenderId = right.id,
                    defenderName = right.name,
                    winnerId = winner?.id,
                    winnerName = winner?.name,
                    status = if (winner == null) "PENDING" else "FINISHED",
                )
            }

    private fun tournamentRound(group: Int): Int = when (group) {
        in 20..27 -> 16
        in 30..33 -> 8
        in 40..41 -> 4
        50 -> 2
        in 60..Int.MAX_VALUE -> 1
        else -> group
    }

    private fun bettingCandidates(lastBettingId: Int, entries: List<TournamentEntry>): List<TournamentBettingCandidate> {
        if (lastBettingId <= 0) {
            return entries.filter { it.group in 20..27 }
                .sortedWith(compareBy<TournamentEntry> { it.group }.thenBy { it.groupNo })
                .mapIndexed { idx, entry ->
                    TournamentBettingCandidate(idx.toString(), entry.name, entry.id.takeIf { it > 0 }, entry.group)
                }
        }
        val row = gameKv.findByTableAndNamespaceAndKey("betting", "betting", "id_$lastBettingId") ?: return emptyList()
        return runCatching {
            val candidates = objectMapper.readTree(row.value).path("candidates")
            candidates.fields().asSequence().mapNotNull { (id, node) -> bettingCandidate(id, node) }.toList()
        }.getOrDefault(emptyList())
    }

    private fun bettingCandidate(id: String, node: JsonNode): TournamentBettingCandidate? {
        val title = node.path("title").takeIf { it.isTextual }?.asText() ?: return null
        val aux = node.path("aux")
        val generalId = aux.path("generalId").takeIf { it.isInt }?.asInt()
            ?: aux.path("generalID").takeIf { it.isInt }?.asInt()
            ?: aux.path("no").takeIf { it.isInt }?.asInt()
        val groupNo = aux.path("group").takeIf { it.isInt }?.asInt()
        return TournamentBettingCandidate(id, title, generalId, groupNo)
    }
}

private data class KnockoutStage(
    val round: Int,
    val sourceBase: Int,
    val matchCount: Int,
    val targetBase: Int,
)

data class TournamentStartRequest(
    val type: Int? = null,
    val tournamentType: Int? = null,
)

data class TournamentCommandAcceptedResponse(
    val status: String,
    val requestId: String,
    val turnIdx: Int,
    val reason: String? = null,
)
