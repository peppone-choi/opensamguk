package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.ClaimableGeneral
import opensamguk.gameapi.dto.ClaimableResponse
import opensamguk.gameapi.dto.ClaimRequest
import opensamguk.gameapi.dto.ClaimResponse
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralPossessionService
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.common.constants.GameConst
import opensamguk.logic.world.SpecialityHelper
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
 *  - `POST /api/general/claim {generalId}` — claim an unowned candidate (account-side INSERT only;
 *    npc-flip deferred — see [GeneralPossessionService]). Idempotent on the same general; 409 on conflict.
 */
@RestController
@RequestMapping("/api")
class PossessionController(
    private val possession: GeneralPossessionService,
    private val owners: GeneralOwnerRepository,
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
) {

    @GetMapping("/generals/claimable")
    fun claimable(@AuthenticationPrincipal userId: Long?): ResponseEntity<ClaimableResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val hasGeneral = owners.findByUserId(userId) != null
        if (hasGeneral) {
            return ResponseEntity.ok(ClaimableResponse(result = true, hasGeneral = true, candidates = emptyList()))
        }

        val claimedIds = owners.findAllByOrderByGeneralIdAsc().map { it.generalId.toInt() }.toSet()
        val nationNames = nations.findAll().associate { it.id to it.name }

        val candidates = generals
            .findByNpcStateOrderByIdAsc(GeneralPossessionService.CLAIMABLE_NPC_STATE)
            .asSequence()
            .filter { it.id !in claimedIds }
            .map { g ->
                ClaimableGeneral(
                    generalId = g.id,
                    name = g.name,
                    nationId = g.nationId,
                    nationName = nationNames[g.nationId],
                    leadership = g.leadership,
                    strength = g.strength,
                    intel = g.intel,
                    picture = g.picture,
                    imageServer = g.imageServer,
                    special = specialName(SpecialityHelper.domesticName(g.specialCode), g.specialCode),
                    special2 = specialName(SpecialityHelper.warName(g.special2Code), g.special2Code),
                    personal = GameConst.personalityNameOf(g.personalCode),
                )
            }
            .toList()

        return ResponseEntity.ok(ClaimableResponse(result = true, hasGeneral = false, candidates = candidates))
    }

    /**
     * 특기 표시 이름 보정. SpecialityHelper.domesticName/warName은 미등록 코드를 그대로 반환하므로,
     * code가 "None"/공백이거나 resolved가 code와 동일(=미해석)하면 '-'로 보정한다(PHP None.php `$name`='-').
     */
    private fun specialName(resolved: String, code: String): String =
        if (code.isBlank() || code == "None") "-" else resolved

    @PostMapping("/general/claim")
    fun claim(
        @AuthenticationPrincipal userId: Long?,
        @RequestBody body: ClaimRequest,
    ): ResponseEntity<ClaimResponse> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val r = possession.claim(userId, body.generalId)) {
            is GeneralPossessionService.ClaimResult.Claimed ->
                ResponseEntity.ok(ClaimResponse(result = true, generalId = r.generalId, reason = null))

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
        }
    }
}
