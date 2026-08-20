package opensamguk.gameapi.controller

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.dto.ClaimableResponse
import opensamguk.gameapi.dto.ClaimRequest
import opensamguk.gameapi.dto.ClaimResponse
import opensamguk.gameapi.owner.GeneralPossessionService
import opensamguk.gameapi.owner.SelectNpcTokenService
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.read.UserRepository
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * F2 Wave 1 — possession ("장수 점유"/빙의) endpoints. Identity-required (the principal is the verified
 * JWT userId injected by [opensamguk.gameapi.security.JwtVerifyFilter]).
 *
 *  - `GET  /api/generals/claimable` — the unowned NPC candidate pool (legacy `npc=2`), minus already
 *    claimed generals, with the caller's `hasGeneral` flag (true ⇒ candidates empty, one-per-user).
 *  - `POST /api/general/claim {generalId}` — record an account-side reservation and publish the daemon
 *    ClaimNpc command that applies the NPC flip. Idempotent on the same general; 409 on conflict.
 */
@RestController
@RequestMapping("/api")
class PossessionController(
    private val possession: GeneralPossessionService,
    private val tokens: SelectNpcTokenService,
    private val reserve: CommandReserveService,
    private val users: UserRepository,
) {

    @GetMapping("/generals/claimable")
    fun claimable(@AuthenticationPrincipal userId: Long?): ResponseEntity<ClaimableResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(tokens.claimable(userId))
    }

    @PostMapping("/general/claim")
    fun claim(
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: ClaimRequest,
    ): ResponseEntity<ClaimResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        // 계정 행이 없으면 여기서 끊는다 — 빙의는 `owner_name` 을 영구 상태·월드 로그에 남긴다.
        val nick = userNick(userId) ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val r = possession.claim(userId, body.generalId) { claimedGeneralId ->
            reserve.publishImmediate(
                TurnDaemonCommand.ClaimNpc(
                    generalId = claimedGeneralId,
                    userId = userId,
                    userNick = nick,
                ),
                // OPENSAM-197 — result-read ownership witness (the claimed general is not yet the
                // caller's, so general_id cannot serve as one).
                ownerUserId = userId.toInt(),
            ).requestId
        }) {
            is GeneralPossessionService.ClaimResult.Claimed -> {
                ResponseEntity.ok(ClaimResponse(result = true, generalId = r.generalId, reason = null, requestId = r.requestId))
            }

            is GeneralPossessionService.ClaimResult.AwaitingDaemon ->
                ResponseEntity.ok(ClaimResponse(result = true, generalId = r.generalId, reason = null, requestId = r.requestId))

            is GeneralPossessionService.ClaimResult.AlreadyOwnedBySelf ->
                ResponseEntity.ok(ClaimResponse(result = true, generalId = r.generalId, reason = "이미 점유한 장수입니다."))

            is GeneralPossessionService.ClaimResult.TerminalDenied ->
                ResponseEntity.ok(ClaimResponse(result = false, generalId = r.generalId, reason = r.reason))

            is GeneralPossessionService.ClaimResult.UncorrelatedReservation ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = r.generalId, reason = "빙의 요청 정보를 확인할 수 없습니다."))

            is GeneralPossessionService.ClaimResult.InvalidClaimResult ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = r.generalId, reason = "빙의 요청 결과를 확인할 수 없습니다."))

            is GeneralPossessionService.ClaimResult.ReservationChanged ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ClaimResponse(result = false, generalId = r.generalId, reason = "빙의 상태가 변경되었습니다. 다시 시도하세요."))

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

    /**
     * 계정 아이디는 토큰이 아니라 `users` 행에서 읽는다(OPENSAM-220).
     * 행이 없으면 null — 호출부가 401 로 끊는다. 이 값은 `owner_name` 과 월드 로그에 영구히 남으므로
     * userId 문자열을 폴백으로 박아 넣지 않는다.
     */
    private fun userNick(userId: Long): String? =
        users.findById(userId).orElse(null)?.username?.takeIf { it.isNotBlank() }
}
