package com.opensam.controller

import com.opensam.dto.ChangePasswordRequest
import com.opensam.dto.UpdateSettingsRequest
import com.opensam.service.AccountService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/account")
class AccountController(
    private val accountService: AccountService,
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
}
