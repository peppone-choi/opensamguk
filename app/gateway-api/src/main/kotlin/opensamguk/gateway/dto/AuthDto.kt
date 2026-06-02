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

    @field:Size(max = 50)
    val nickname: String? = null,
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
)
