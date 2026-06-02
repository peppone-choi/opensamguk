package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.ChiefPost
import opensamguk.gameapi.dto.ChiefReservedResponse
import opensamguk.gameapi.dto.ChiefReservedTurn
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.F4StateText
import opensamguk.gameapi.read.NationTurnReadRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F4 — `GET /api/nation/chief-reserved` (사령부, spec page 7). READ-only.
 *
 * Identity-required (the caller's nation is resolved from the verified principal — this is per-nation
 * privileged data). Returns the 8 chief posts (officer levels 12/11/10/9/8/7/6/5, verbatim titles) each
 * with its reserved nation-command turns from `nation_turn` (keyed by officer_level). `maxChiefTurn` is
 * the legacy reserved-turn cap. A caller with no character → 401. A caller in 재야 (no nation) → the 8
 * posts with empty reserved-turn lists (200). `nation_turn` carries no chief rows in the fresh seed →
 * empty lists, never 500.
 */
@RestController
@RequestMapping("/api/nation")
class ChiefCenterController(
    private val resolver: GeneralResolver,
    private val nationTurns: NationTurnReadRepository,
) {

    /** Legacy reserved-turn cap (maxChiefTurn). The 30-turn ring is the nation-command max. */
    private val maxChiefTurn = 12

    @GetMapping("/chief-reserved")
    fun chiefReserved(@AuthenticationPrincipal userId: Long?): ResponseEntity<ChiefReservedResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val nationId = resolved.nationId

        val byLevel: Map<Int, List<ChiefReservedTurn>> = if (nationId != 0) {
            nationTurns.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(nationId)
                .groupBy { it.officerLevel }
                .mapValues { (_, rows) ->
                    rows.sortedBy { it.turnIdx }
                        .map { ChiefReservedTurn(turnIdx = it.turnIdx, actionCode = it.actionCode, brief = it.brief) }
                }
        } else {
            emptyMap()
        }

        val posts = F4StateText.CHIEF_POSTS.map { meta ->
            ChiefPost(
                officerLevel = meta.officerLevel,
                title = meta.title,
                reservedTurns = byLevel[meta.officerLevel] ?: emptyList(),
            )
        }

        return ResponseEntity.ok(
            ChiefReservedResponse(
                result = true,
                nationId = nationId,
                maxChiefTurn = maxChiefTurn,
                posts = posts,
            ),
        )
    }
}
