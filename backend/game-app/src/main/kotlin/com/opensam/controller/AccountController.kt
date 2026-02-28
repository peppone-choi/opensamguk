package com.opensam.controller

import com.opensam.dto.ChangePasswordRequest
import com.opensam.dto.UpdateSettingsRequest
import com.opensam.engine.RealtimeService
import com.opensam.service.AccountService
import com.opensam.service.GeneralService
import com.opensam.service.IconSyncService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Paths
import java.util.UUID

@RestController
@RequestMapping("/api/account")
class AccountController(
    private val accountService: AccountService,
    private val iconSyncService: IconSyncService,
    private val generalService: GeneralService,
    private val realtimeService: RealtimeService,
) {
    private fun getLoginId(): String? = SecurityContextHolder.getContext().authentication?.name

    @PatchMapping("/password")
    fun changePassword(@RequestBody request: ChangePasswordRequest): ResponseEntity<Void> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!accountService.changePassword(loginId, request.currentPassword, request.newPassword)) {
            return ResponseEntity.badRequest().build()
        }
        return ResponseEntity.ok().build()
    }

    @DeleteMapping
    fun deleteAccount(): ResponseEntity<Void> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!accountService.deleteAccount(loginId)) return ResponseEntity.notFound().build()
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/settings")
    fun updateSettings(@RequestBody request: UpdateSettingsRequest): ResponseEntity<Void> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!accountService.updateSettings(
                loginId = loginId,
                defenceTrain = request.defenceTrain,
                tournamentState = request.tournamentState,
                potionThreshold = request.potionThreshold,
                autoNationTurn = request.autoNationTurn,
                preRiseDelete = request.preRiseDelete,
                preOpenDelete = request.preOpenDelete,
                borderReturn = request.borderReturn,
                customCss = request.customCss,
            )) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok().build()
    }

    @PostMapping("/vacation")
    fun toggleVacation(): ResponseEntity<Void> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!accountService.toggleVacation(loginId)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PostMapping("/icon", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadIcon(@RequestParam("icon") file: MultipartFile): ResponseEntity<Map<String, String>> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val contentType = file.contentType ?: "image/jpeg"
        if (!contentType.startsWith("image/")) return ResponseEntity.badRequest().build()
        val ext = when {
            contentType.contains("png") -> "png"
            contentType.contains("gif") -> "gif"
            contentType.contains("webp") -> "webp"
            else -> "jpg"
        }
        val uploadDir = Paths.get(System.getProperty("user.home"), ".opensam", "icons")
        Files.createDirectories(uploadDir)
        val filename = "${UUID.randomUUID()}.$ext"
        val dest = uploadDir.resolve(filename)
        file.transferTo(dest)
        val iconUrl = "/uploads/icons/$filename"
        if (!accountService.updateIconUrl(loginId, iconUrl)) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(mapOf("url" to iconUrl))
    }

    @DeleteMapping("/icon")
    fun deleteIcon(): ResponseEntity<Void> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (!accountService.updateIconUrl(loginId, "")) return ResponseEntity.notFound().build()
        return ResponseEntity.ok().build()
    }

    @PostMapping("/icon/sync")
    fun syncIcon(): ResponseEntity<Any> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(iconSyncService.syncIcon(loginId))
    }

    @GetMapping("/detailed-info")
    fun getDetailedInfo(): ResponseEntity<Map<String, Any?>> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val info = accountService.getDetailedInfo(loginId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(info)
    }

    @PostMapping("/buildNationCandidate")
    fun buildNationCandidate(): ResponseEntity<Any> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.getMyActiveGeneral(loginId)
            ?: return ResponseEntity.badRequest().build()
        val result = realtimeService.submitCommand(general.id, "거병", null)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/instantRetreat")
    fun instantRetreat(): ResponseEntity<Any> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.getMyActiveGeneral(loginId)
            ?: return ResponseEntity.badRequest().build()
        val result = realtimeService.submitCommand(general.id, "접경귀환", null)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/dieOnPrestart")
    fun dieOnPrestart(): ResponseEntity<Any> {
        val loginId = getLoginId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val general = generalService.getMyActiveGeneral(loginId)
            ?: return ResponseEntity.badRequest().build()
        val result = realtimeService.submitCommand(general.id, "사전거병삭제", null)
        return ResponseEntity.ok(result)
    }
}
