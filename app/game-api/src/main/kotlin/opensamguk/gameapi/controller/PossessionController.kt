package opensamguk.gameapi.controller

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.dto.ClaimableResponse
import opensamguk.gameapi.dto.ClaimRequest
import opensamguk.gameapi.dto.ClaimResponse
import opensamguk.gameapi.owner.GeneralPossessionService
import opensamguk.gameapi.owner.SelectNpcTokenService
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 1 — possession ("장수 점유"/빙의) endpoints. Identity-required (the principal is the verified
 * JWT userId injected by [opensamguk.gameapi.security.JwtVerifyFilter]).
 *
 *  - `GET  /api/generals/claimable` — the unowned NPC candidate pool (legacy `npc=2`), minus already
 *    claimed generals, with the caller's `hasGeneral` flag (true ⇒ candidates empty, one-per-user).
 *  - `POST /api/general/claim {generalId}` — claim an unowned candidate (account-side INSERT only;
 *    npc-flip deferred — see [GeneralPossessionService]). Idempotent on the same general; 409 on conflict.
 */
@RestController
@RequestMapping("/api")
class PossessionController(
    private val possession: GeneralPossessionService,
    private val tokens: SelectNpcTokenService,
    private val reserve: CommandReserveService,
    private val jwtVerifier: GameApiJwtVerifier,
) {

    @GetMapping("/generals/claimable")
    fun claimable(@AuthenticationPrincipal userId: Long?): ResponseEntity<ClaimableResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(tokens.claimable(userId))
    }

    @PostMapping("/general/claim")
    fun claim(
        @AuthenticationPrincipal userId: Long?,
        @RequestHeader(HttpHeaders.AUTHORIZATION, required = false) authorization: String?,
        @RequestBody body: ClaimRequest,
    ): ResponseEntity<ClaimResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val r = possession.claim(userId, body.generalId) { claimedGeneralId ->
            reserve.publishImmediate(
                TurnDaemonCommand.ClaimNpc(
                    generalId = claimedGeneralId,
                    userId = userId,
                    userNick = userNick(userId, authorization),
                ),
            ).requestId
        }) {
            is GeneralPossessionService.ClaimResult.Claimed -> {
                ResponseEntity.ok(ClaimResponse(result = true, generalId = r.generalId, reason = null, requestId = r.requestId))
            }

            is GeneralPossessionService.ClaimResult.AlreadyOwnedBySelf ->
                ResponseEntity.ok(ClaimResponse(result = true, generalId = r.generalId, reason = "이미 점유한 장수입니다."))

            is GeneralPossessionService.ClaimResult.UserAlreadyHasGeneral ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = r.ownedGeneralId, reason = "이미 다른 장수를 점유하고 있습니다."))

            GeneralPossessionService.ClaimResult.GeneralAlreadyClaimed ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = null, reason = "이미 점유된 장수입니다."))

            GeneralPossessionService.ClaimResult.NotClaimable ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = null, reason = "빙의 가능한 장수가 아닙니다."))

            GeneralPossessionService.ClaimResult.ServerModeBlocked ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = null, reason = SelectNpcTokenService.NPC_MODE_BLOCKED_REASON))
        }
    }

    private fun userNick(userId: Long, authorization: String?): String {
        val token = authorization
            ?.takeIf { it.startsWith("Bearer ") }
            ?.substringAfter("Bearer ")
        return token?.let { jwtVerifier.getUsername(it) }?.takeIf { it.isNotBlank() } ?: userId.toString()
    }
}
