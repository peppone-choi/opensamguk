package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.dto.TournamentRankingBoard
import opensamguk.gameapi.dto.TournamentResponse
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.GameKvReadRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/tournament` (spec page 12, 13-read, 11-bracket).
 *
 * READ-only. There is NO dedicated tournament table in this schema; the legacy tournament state lives
 * in the `game_env` string-namespace KV bag (`b_tournament.php`: keys `tournament`, `phase`,
 * `turnterm`, `tnmt_msg`, `tnmt_type`). In the fresh scenario_1010 seed NONE of these keys exist, so
 * this endpoint returns the documented STATE-0 default (no tournament running) gracefully — empty
 * groups/bracket, the 4 ranking boards present-but-empty, tnmtType 0 → 전력전. No fabrication.
 *
 * Public: no principal required (the 토너먼트 view page is anonymous-readable).
 */
@RestController
@RequestMapping("/api/tournament")
class TournamentController(
    private val gameKv: GameKvReadRepository,
    private val objectMapper: ObjectMapper,
) {

    private fun kvInt(key: String, default: Int): Int {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key) ?: return default
        return runCatching { objectMapper.readTree(row.value).asInt(default) }.getOrDefault(default)
    }

    private fun kvText(key: String, default: String): String {
        val row = gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", key) ?: return default
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

        // No tournament rows in the seed → empty groups/bracket; the 4 ranking boards are always
        // present (verbatim labels) but empty until a tournament runs.
        val rankings = F4StateText.RANKING_TYPES.map { TournamentRankingBoard(type = it, rows = emptyList()) }

        return ResponseEntity.ok(
            TournamentResponse(
                state = state,
                tnmtType = tnmtType,
                tnmtTypeText = F4StateText.tournamentTypeText(tnmtType),
                tnmtMsg = tnmtMsg,
                turnTerm = turnTerm,
                groups = emptyList(),
                bracket = emptyList(),
                rankings = rankings,
            ),
        )
    }
}
