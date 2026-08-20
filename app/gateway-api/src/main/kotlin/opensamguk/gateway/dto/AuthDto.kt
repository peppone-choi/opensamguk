package opensamguk.gateway.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

/** 회원가입 요청 */
data class RegisterRequest(
    @field:NotBlank
    @field:Size(min = 3, max = 50)
    val username: String,

    @field:NotBlank
    @field:Size(min = 6, max = 100)
    val password: String,

    @field:Email
    val email: String? = null,

    @field:NotBlank
    @field:Size(min = 2, max = 20)
    val nickname: String,
)

/** 로그인 요청 */
data class LoginRequest(
    @field:NotBlank
    val username: String,

    @field:NotBlank
    val password: String,
)

/** 토큰 재발급 요청 */
data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

/** 인증 응답 */
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserResponse,
)

/** 사용자 정보 응답 */
data class UserResponse(
    val id: Long,
    val username: String,
    val email: String?,
    val nickname: String?,
    val role: String,
    val picture: String? = null,
    val imageServer: Int = 0,
)

data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,

    @field:NotBlank
    @field:Size(min = 6, max = 100)
    val newPassword: String,
)

data class ProfileIconRequest(
    @field:Size(max = 64)
    val picture: String? = null,

    val imgsvr: Int = 0,
)

data class DeleteAccountRequest(
    @field:NotBlank
    val currentPassword: String,
)
