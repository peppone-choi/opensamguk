package com.opensam.gateway.controller

import com.opensam.gateway.service.AccountOAuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


data class OAuthCallbackRequest(
    val code: String,
    val redirectUri: String,
)

@RestController
@RequestMapping("/api/account/oauth")
class AccountOAuthController(
    private val accountOAuthService: AccountOAuthService,
) {
    private fun currentUserId(): Long? = SecurityContextHolder.getContext().authentication?.name?.toLongOrNull()

    @GetMapping
    fun getOAuthProviders(): ResponseEntity<Any> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return ResponseEntity.ok(accountOAuthService.getOAuthProviders(userId))
    }

    @PostMapping("/{provider}/link")
    fun linkOAuth(
        @PathVariable provider: String,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val origin = request.getHeader("Origin")
        return ResponseEntity.ok(accountOAuthService.startLinkOAuth(userId, provider, origin))
    }

    @PostMapping("/{provider}/callback")
    fun completeLinkOAuth(
        @PathVariable provider: String,
        @RequestBody request: OAuthCallbackRequest,
    ): ResponseEntity<Void> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        accountOAuthService.completeLinkOAuth(userId, provider, request.code, request.redirectUri)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{provider}")
    fun unlinkOAuth(@PathVariable provider: String): ResponseEntity<Void> {
        val userId = currentUserId() ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        accountOAuthService.unlinkOAuth(userId, provider)
        return ResponseEntity.noContent().build()
    }
}
