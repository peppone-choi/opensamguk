package opensamguk.gateway.controller

import jakarta.validation.Valid
import opensamguk.gateway.dto.RepresentativeResponse
import opensamguk.gateway.dto.SetRepresentativeRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.RepresentativeService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** ADR-LITE-049 13 — `GET/POST /auth/account/representative` 계정 대표 장수. 인증 필수(/auth/account 하위 규칙). */
@RestController
@RequestMapping("/auth/account/representative")
class RepresentativeController(
    private val representativeService: RepresentativeService,
) {
    @GetMapping
    fun current(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<RepresentativeResponse> =
        ResponseEntity.ok(representativeService.current(userDetails))

    @PostMapping
    fun set(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: SetRepresentativeRequest,
    ): ResponseEntity<RepresentativeResponse> = ResponseEntity.ok(representativeService.set(userDetails, request.generalId))
}
