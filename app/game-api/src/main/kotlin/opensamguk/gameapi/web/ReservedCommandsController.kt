package opensamguk.gameapi.web

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralTurnReadRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 6 — `GET /api/reserved-commands?generalId=` (spec §5). The general's reserved-turn ring:
 * the durable `general_turn` slots the daemon will execute, rendered as the reserved-command panel.
 *
 * READ-ONLY: the daemon owns the ring write (JDBC `ReservedTurnRepository` — §0.1 #3); this controller
 * only reads it through the [GeneralTurnReadRepository] JPA read entity. Each slot is returned as
 * `{turnIdx, action, brief, arg}` ordered by `turn_idx` (the ring slot order). A general with no
 * reserved rows returns an empty `slots` list (the daemon default-seeds 휴식 lazily on reserve).
 *
 * Identity: the verified JWT principal resolves the caller's own general; the `?generalId=` query
 * param is the F2 transition fallback, gated OFF (403) when it does not match the principal's owned
 * general (Task 4 hardening — a player may only read their OWN reserved ring).
 */
@RestController
@RequestMapping("/api")
class ReservedCommandsController(
    private val resolver: GeneralResolver,
    private val reservedTurns: GeneralTurnReadRepository,
) {

    /** One reserved ring slot (matches the W5 reserved-command panel shape). */
    data class ReservedSlot(
        val turnIdx: Int,
        val action: String,
        val brief: String,
        val arg: Map<String, Any?>,
    )

    /** The reserved-ring envelope. */
    data class ReservedCommandsResponse(
        val result: Boolean,
        val generalId: Int?,
        val slots: List<ReservedSlot>,
    )

    @GetMapping("/reserved-commands")
    fun reservedCommands(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam(required = false) generalId: Int?,
    ): ResponseEntity<Any> {
        val resolvedId = userId?.let { resolver.resolveGeneralId(it) }
        if (userId != null && generalId != null && generalId != resolvedId) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val effectiveId = resolvedId ?: generalId
            ?: return ResponseEntity.ok(ReservedCommandsResponse(result = false, generalId = null, slots = emptyList()))

        val slots = reservedTurns.findByGeneralIdOrderByTurnIdxAsc(effectiveId).map { row ->
            ReservedSlot(
                turnIdx = row.turnIdx,
                action = row.actionCode,
                brief = row.brief,
                arg = row.arg,
            )
        }
        return ResponseEntity.ok(ReservedCommandsResponse(result = true, generalId = effectiveId, slots = slots))
    }
}
