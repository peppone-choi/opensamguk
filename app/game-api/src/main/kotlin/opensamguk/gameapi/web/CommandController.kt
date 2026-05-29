package opensamguk.gameapi.web

import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.reserve.CommandReserveService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Step 1 + step 2 of the 8-step flow, exposed to the Next.js client.
 *
 * `POST /api/command/{code}?generalId=` runs the E2 precheck (the SHARED `:logic` constraints) and,
 * ONLY when it returns `AVAILABLE`, runs the E3 reserve (durable `general_turn` + daemon poke):
 *
 *  - `AVAILABLE` → `202 Accepted` carrying the reserve `requestId` (the command is queued; the daemon
 *    will process it on its next run).
 *  - `BLOCKED`   → `200 OK` carrying the PHP-faithful deny reason (NOT an error — the player simply
 *    cannot run the command right now; the UI shows the reason).
 *  - `UNKNOWN`   → `200 OK` carrying a generic unavailable reason (a precheck input row was absent).
 *
 * `turnIdx` defaults to `0` (the next reservable slot); the TS `setGeneralTurn` route accepts an
 * explicit `turnIndex` (0..MAX-1) chosen by the caller, so it is an optional query param here.
 */
@RestController
@RequestMapping("/api/command")
class CommandController(
    private val precheck: CommandPrecheckService,
    private val reserve: CommandReserveService,
) {
    /** The JSON body of a 202 reserve response. */
    data class ReservedResponse(val status: String, val requestId: String, val turnIdx: Int)

    /** The JSON body of a 200 non-reservable response. */
    data class BlockedResponse(val status: String, val reason: String, val constraintName: String? = null)

    @PostMapping("/{code}")
    fun command(
        @PathVariable code: String,
        @RequestParam generalId: Int,
        @RequestParam(required = false, defaultValue = "0") turnIdx: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<Any> =
        when (val result = precheck.precheck(generalId = generalId, actionCode = code)) {
            PrecheckResult.Available -> {
                val reserved = reserve.reserve(
                    generalId = generalId,
                    actionCode = code,
                    turnIdx = turnIdx,
                    argJson = argJson,
                )
                ResponseEntity.status(HttpStatus.ACCEPTED).body(
                    ReservedResponse(status = "AVAILABLE", requestId = reserved.requestId, turnIdx = reserved.turnIdx),
                )
            }

            is PrecheckResult.Blocked -> ResponseEntity.ok(
                BlockedResponse(status = "BLOCKED", reason = result.reason, constraintName = result.constraintName),
            )

            is PrecheckResult.Unknown -> ResponseEntity.ok(
                BlockedResponse(status = "UNKNOWN", reason = "명령을 확인할 수 없습니다."),
            )
        }
}
