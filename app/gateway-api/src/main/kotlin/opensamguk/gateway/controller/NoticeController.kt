package opensamguk.gateway.controller

import jakarta.validation.Valid
import opensamguk.gateway.dto.NoticeListResponse
import opensamguk.gateway.dto.NoticePinRequest
import opensamguk.gateway.dto.NoticeResponse
import opensamguk.gateway.dto.NoticeUpsertRequest
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.NoticeService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 공지 — 공개 읽기 `GET /notices`(SecurityConfig permitAll) + 관리 `/admin/notices/…`(`/admin/` 하위 전체 = ROLE_ADMIN).
 * 로그인·로비 우측 「공지」와 운영 콘솔 「공지」 섹션이 쓴다(ADR-LITE-049 Phase 2·3).
 */
@RestController
class NoticeController(private val notices: NoticeService) {
    @GetMapping("/notices")
    fun publicFeed(@RequestParam(required = false) limit: Int?): ResponseEntity<NoticeListResponse> =
        ResponseEntity.ok(NoticeListResponse(notices.publicFeed(limit ?: NoticeService.PUBLIC_LIMIT)))

    @GetMapping("/admin/notices")
    fun adminList(): ResponseEntity<NoticeListResponse> =
        ResponseEntity.ok(NoticeListResponse(notices.adminList()))

    @PostMapping("/admin/notices")
    fun create(
        @AuthenticationPrincipal principal: CustomUserDetails?,
        @Valid @RequestBody request: NoticeUpsertRequest,
    ): ResponseEntity<NoticeResponse> =
        ResponseEntity.ok(notices.create(request, principal?.id))

    @PutMapping("/admin/notices/{id}")
    fun update(@PathVariable id: Long, @Valid @RequestBody request: NoticeUpsertRequest): ResponseEntity<NoticeResponse> =
        ResponseEntity.ok(notices.update(id, request))

    @PatchMapping("/admin/notices/{id}/pin")
    fun pin(@PathVariable id: Long, @RequestBody request: NoticePinRequest): ResponseEntity<NoticeResponse> =
        ResponseEntity.ok(notices.setPinned(id, request.pinned))

    @DeleteMapping("/admin/notices/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<NoticeResponse> =
        ResponseEntity.ok(notices.delete(id))
}
