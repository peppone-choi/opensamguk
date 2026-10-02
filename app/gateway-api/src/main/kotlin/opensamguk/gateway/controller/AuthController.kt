package opensamguk.gateway.controller

import jakarta.validation.Valid
import opensamguk.gateway.dto.AuthResponse
import opensamguk.gateway.dto.AuthPolicyResponse
import opensamguk.gateway.dto.ChangeNicknameRequest
import opensamguk.gateway.dto.ChangePasswordRequest
import opensamguk.gateway.dto.DeleteAccountRequest
import opensamguk.gateway.dto.LoginRequest
import opensamguk.gateway.dto.RefreshRequest
import opensamguk.gateway.dto.RegisterRequest
import opensamguk.gateway.dto.UserResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.AuthService
import opensamguk.gateway.service.AuthPolicyUnavailableException
import org.springframework.dao.DataAccessException
import org.springframework.http.ResponseEntity
import org.springframework.http.CacheControl
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.transaction.TransactionException
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
) {

    @GetMapping("/policy")
    fun policy(): ResponseEntity<AuthPolicyResponse> {
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.policy())
        } catch (e: TransactionException) {
            // Transaction advice can fail before the service body or after it returns.
            throw AuthPolicyUnavailableException(e)
        } catch (e: DataAccessException) {
            // The JPA transaction manager can translate commit failures to data access errors.
            throw AuthPolicyUnavailableException(e)
        }
    }

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): ResponseEntity<AuthResponse> {
        val response = authService.register(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<AuthResponse> {
        val response = authService.login(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): ResponseEntity<AuthResponse> {
        val response = authService.refresh(request.refreshToken)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal userDetails: CustomUserDetails): ResponseEntity<UserResponse> {
        val response = authService.me(userDetails)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/account/password")
    fun changePassword(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<Void> {
        authService.changePassword(userDetails, request)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/account/nickname")
    fun changeNickname(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ChangeNicknameRequest,
    ): ResponseEntity<AuthResponse> = ResponseEntity.ok(authService.changeNickname(userDetails, request))

    @DeleteMapping("/account")
    fun deleteAccount(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: DeleteAccountRequest,
    ): ResponseEntity<Void> {
        authService.deleteAccount(userDetails, request)
        return ResponseEntity.noContent().build()
    }
}
